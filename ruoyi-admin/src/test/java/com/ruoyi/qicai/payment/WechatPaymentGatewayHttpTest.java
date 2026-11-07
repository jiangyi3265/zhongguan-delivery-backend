package com.ruoyi.qicai.payment;

import com.sun.net.httpserver.HttpServer;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.ruoyi.qicai.QicaiApiException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Real local HTTP transport through official SDK; synthetic signer/provider, never WeChat. */
class WechatPaymentGatewayHttpTest
{
    PaymentCryptoFixture f;HttpServer server;
    AtomicReference<String> reply=new AtomicReference<String>("{\"prepay_id\":\"wxsynthetic123\"}");
    AtomicReference<Integer> status=new AtomicReference<Integer>(200);
    AtomicReference<Boolean> validSignature=new AtomicReference<Boolean>(true);
    AtomicReference<String> headerMode=new AtomicReference<String>("normal");
    AtomicReference<String> requestBody=new AtomicReference<String>(),authorization=new AtomicReference<String>(),requestPath=new AtomicReference<String>();
    AtomicReference<String> requestId=new AtomicReference<String>("request-local-default");
    AtomicReference<Long> delayMillis=new AtomicReference<Long>(0L);
    AtomicReference<CountDownLatch> responseGate=new AtomicReference<>();
    Map<String,String> idsByPath=new ConcurrentHashMap<>(),codesByPath=new ConcurrentHashMap<>();
    ExecutorService providerExecutor;
    List<Integer> providerConnections = new CopyOnWriteArrayList<>();
    Logger logger; Level previousLevel; ListAppender<ILoggingEvent> diagnosticLogs;
    @BeforeEach void setup() throws Exception
    {
        f=new PaymentCryptoFixture();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        logger=(Logger)LoggerFactory.getLogger(OfficialWechatPaymentGateway.class);previousLevel=logger.getLevel();logger.setLevel(Level.INFO);
        diagnosticLogs=new ListAppender<>();diagnosticLogs.start();logger.addAppender(diagnosticLogs);
        providerExecutor=Executors.newFixedThreadPool(4);server.setExecutor(providerExecutor);
        server.createContext("/",exchange->{try{
            providerConnections.add(exchange.getRemoteAddress().getPort());
            requestBody.set(new String(org.springframework.util.StreamUtils.copyToByteArray(exchange.getRequestBody()),StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));requestPath.set(exchange.getRequestURI().toString());
            String currentPath=exchange.getRequestURI().getPath();
            CountDownLatch gate=responseGate.get();
            if(gate!=null){gate.countDown();if(!gate.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Local response gate did not receive concurrent calls");}
            if(delayMillis.get()>0)Thread.sleep(delayMillis.get());
            String body=codesByPath.containsKey(currentPath)?"{\"code\":\""+codesByPath.get(currentPath)+"\",\"message\":\"BE05_PRIVATE_MARKER\"}":reply.get();
            String nonce="synthetic-response",timestamp=Long.toString(Instant.now().getEpochSecond());
            Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(f.platform.getPrivate());signer.update((timestamp+"\n"+nonce+"\n"+body+"\n").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Wechatpay-Serial",f.properties.getPublicKeyId());
            exchange.getResponseHeaders().set("Wechatpay-Nonce",nonce);exchange.getResponseHeaders().set("Wechatpay-Timestamp",timestamp);
            exchange.getResponseHeaders().set("Wechatpay-Signature",validSignature.get()?Base64.getEncoder().encodeToString(signer.sign()):"invalid");
            String trace=idsByPath.containsKey(currentPath)?idsByPath.get(currentPath):requestId.get();
            if(trace!=null)exchange.getResponseHeaders().set("Request-ID",trace);
            if(headerMode.get().equals("duplicate-request-id"))exchange.getResponseHeaders().add("request-id","request-ambiguous");
            if(headerMode.get().equals("duplicate-timestamp"))exchange.getResponseHeaders().add("wechatpay-timestamp",timestamp);
            if(headerMode.get().equals("duplicate-signature"))exchange.getResponseHeaders().add("wEcHaTpAy-SiGnAtUrE",exchange.getResponseHeaders().getFirst("Wechatpay-Signature"));
            if(headerMode.get().equals("missing-timestamp"))exchange.getResponseHeaders().remove("Wechatpay-Timestamp");
            if(headerMode.get().equals("missing-signature"))exchange.getResponseHeaders().remove("Wechatpay-Signature");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status.get(),status.get()==204?-1:bytes.length);if(status.get()!=204)exchange.getResponseBody().write(bytes);
        }catch(Exception e){throw new RuntimeException(e);}finally{exchange.close();}});server.start();
        WechatPayProperties p=f.properties;RSAPublicKeyConfig config=new RSAPublicKeyConfig.Builder().merchantId(p.getMerchantId()).merchantSerialNumber(p.getMerchantSerialNumber())
            .privateKeyFromPath(p.getPrivateKeyPath()).publicKeyFromPath(p.getPublicKeyPath()).publicKeyId(p.getPublicKeyId()).apiV3Key(f.apiKey).build();
        // Test-only local rewrite after SDK signing; production has no endpoint override.
        okhttp3.OkHttpClient client=OfficialWechatPaymentGateway.paymentHttpClient().newBuilder().addInterceptor(chain->chain.proceed(chain.request().newBuilder()
                .url(chain.request().url().newBuilder().scheme("http").host("127.0.0.1").port(server.getAddress().getPort()).build()).build()))
                .build();
        JsapiServiceExtension service=new JsapiServiceExtension.Builder().config(config).httpClient(new DefaultHttpClientBuilder().config(config).okHttpClient(client).build()).build();
        ReflectionTestUtils.setField(f.official,"service",service);
        ReflectionTestUtils.setField(f.official,"refundService",new com.wechat.pay.java.service.refund.RefundService.Builder().config(config).httpClient(new DefaultHttpClientBuilder().config(config).okHttpClient(client).build()).build());
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);if(providerExecutor!=null)providerExecutor.shutdownNow();
        if(logger!=null){logger.detachAppender(diagnosticLogs);logger.setLevel(previousLevel);}if(diagnosticLogs!=null)diagnosticLogs.stop();}

    List<ILoggingEvent> diagnostics(){return diagnosticLogs.list.stream().filter(e->e.getFormattedMessage().startsWith("wechat_payment_diagnostic ")).collect(Collectors.toList());}
    String singleDiagnostic()
    {
        List<ILoggingEvent> entries=diagnostics();assertEquals(1,entries.size(),"Exactly one diagnostic per failed SDK call");
        ILoggingEvent entry=entries.get(0);assertNull(entry.getThrowableProxy(),"SDK exceptions must never be attached to diagnostic logs");
        for(Object argument:entry.getArgumentArray())assertFalse(argument instanceof Throwable);
        String text=entry.getFormattedMessage();
        assertTrue(text.matches("wechat_payment_diagnostic op=[a-z_]+ kind=[a-z_]+ status=[0-9]{1,3} code=[A-Za-z_]+ request_id=[A-Za-z0-9_-]+ correlation=[a-f0-9-]{36} elapsed_ms=[0-9]+"));
        for(String secret:Arrays.asList("BE05_PRIVATE_MARKER","BE05-SECRET-OPENID","BE05-SECRET-TRADE","BE05-SECRET-REFUND",f.apiKey,"synthetic-app-secret","Authorization","paySign","BEGIN PRIVATE KEY"))
            assertFalse(text.contains(secret),"Unexpected private value in diagnostic: "+secret);
        return text;
    }
    @Test void requestSignatureAmountAndSignedReplyProduceValidMiniPaymentSignature() throws Exception
    {
        Map<String,Object> params=f.official.prepay("QC123",1913,"synthetic-owner","2026-12-31T01:30:00Z");
        assertEquals("prepay_id=wxsynthetic123",params.get("package"));assertEquals(1913,f.json.readTree(requestBody.get()).path("amount").path("total").asInt());
        assertEquals("2026-12-31T01:30:00Z",f.json.readTree(requestBody.get()).path("time_expire").asText());
        assertEquals("synthetic-owner",f.json.readTree(requestBody.get()).path("payer").path("openid").asText());
        Map<String,String> auth=new HashMap<String,String>();Matcher m=Pattern.compile("([a-z_]+)=\"([^\"]*)\"").matcher(authorization.get());while(m.find())auth.put(m.group(1),m.group(2));
        Signature verify=Signature.getInstance("SHA256withRSA");verify.initVerify(f.merchant.getPublic());
        verify.update(("POST\n"+requestPath.get()+"\n"+auth.get("timestamp")+"\n"+auth.get("nonce_str")+"\n"+requestBody.get()+"\n").getBytes(StandardCharsets.UTF_8));assertTrue(verify.verify(Base64.getDecoder().decode(auth.get("signature"))));
        verify.initVerify(f.merchant.getPublic());verify.update((f.properties.getAppId()+"\n"+params.get("timeStamp")+"\n"+params.get("nonceStr")+"\n"+params.get("package")+"\n").getBytes(StandardCharsets.UTF_8));assertTrue(verify.verify(Base64.getDecoder().decode((String)params.get("paySign"))));
    }
    @Test void malformedOrUnsignedSuccessfulReplyCannotProducePaymentParameters()
    {
        for(String body:Arrays.asList("{}","{\"prepay_id\":\"\"}","{\"prepay_id\":null}")){
            reply.set(body);assertFalse(assertThrows(QicaiApiException.class,()->f.official.prepay("QC123",1913,"synthetic-owner","2026-12-31T01:30:00Z")) instanceof WechatPrepayRejectedException);}
        reply.set("{\"prepay_id\":\"wxsynthetic123\"}");validSignature.set(false);
        assertFalse(assertThrows(QicaiApiException.class,()->f.official.prepay("QC123",1913,"synthetic-owner","2026-12-31T01:30:00Z")) instanceof WechatPrepayRejectedException);
    }
    @Test void explicitRefusalIsDistinctFromAmbiguousProviderFailureAndUnknownClose()
    {
        status.set(400);validSignature.set(false);
        for(String code:Arrays.asList("PARAM_ERROR","APPID_MCHID_NOT_MATCH","NO_AUTH")) {
            reply.set("{\"code\":\""+code+"\",\"message\":\"synthetic rejected\"}");
            assertThrows(WechatPrepayRejectedException.class,()->f.official.prepay("QC123",1913,"synthetic-owner","2026-12-31T01:30:00Z")); }
        status.set(500);reply.set("{\"code\":\"SYSTEM_ERROR\",\"message\":\"synthetic unknown\"}");
        assertFalse(assertThrows(QicaiApiException.class,()->f.official.prepay("QC123",1913,"synthetic-owner","2026-12-31T01:30:00Z")) instanceof WechatPrepayRejectedException);
        status.set(404);reply.set("{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"synthetic missing\"}");
        assertNull(f.official.query("QC123"));assertThrows(QicaiApiException.class,()->f.official.close("QC123"));
    }
    @Test void signedQueryIsReturnedButTamperedQueryIsRefused() throws Exception
    {
        reply.set(f.json.writeValueAsString(f.transaction("QC123",1913,"synthetic-owner")));
        assertEquals(1913,f.official.query("QC123").getAmount().getTotal());
        validSignature.set(false);assertThrows(QicaiApiException.class,()->f.official.query("QC123"));
    }
    @Test void duplicateMixedCaseAndMissingSignatureHeadersCannotPass()
    {
        assertEquals("prepay_id=wxsynthetic123",f.official.prepay("QC123",1913,"synthetic-owner","2026-12-31T01:30:00Z").get("package"));
        for(String mode:Arrays.asList("duplicate-timestamp","duplicate-signature","missing-timestamp","missing-signature")) {
            headerMode.set(mode);
            assertFalse(assertThrows(QicaiApiException.class,()->f.official.prepay("QC123",1913,"synthetic-owner","2026-12-31T01:30:00Z")) instanceof WechatPrepayRejectedException);
        }
    }

    @Test void refundCreateAndQueryUseSignedSdkTransportAndRejectTampering() throws Exception {
        reply.set("{\"refund_id\":\"wxrefund123\",\"out_refund_no\":\"RF123\",\"out_trade_no\":\"QC123\",\"transaction_id\":\"TX123\",\"status\":\"PROCESSING\",\"amount\":{\"total\":1913,\"refund\":1913,\"currency\":\"CNY\"}}");
        assertEquals("RF123",f.official.refund("QC123","RF123",1913,"synthetic").getOutRefundNo());
        assertEquals("/v3/refund/domestic/refunds",requestPath.get());assertEquals(1913,f.json.readTree(requestBody.get()).path("amount").path("refund").asInt());assertEquals(1913,f.json.readTree(requestBody.get()).path("amount").path("total").asInt());
        assertEquals("wxrefund123",f.official.queryRefund("RF123").getRefundId());assertEquals("/v3/refund/domestic/refunds/RF123",requestPath.get());
        validSignature.set(false);assertThrows(QicaiApiException.class,()->f.official.queryRefund("RF123"));assertThrows(QicaiApiException.class,()->f.official.refund("QC123","RF123",1913,"synthetic"));
        status.set(404);reply.set("{\"code\":\"RESOURCE_NOT_EXISTS\",\"message\":\"synthetic missing\"}");assertNull(f.official.queryRefund("RF123"));
        status.set(500);reply.set("{\"code\":\"SYSTEM_ERROR\",\"message\":\"synthetic unknown\"}");assertThrows(QicaiApiException.class,()->f.official.queryRefund("RF123"));
    }

    @Test void serviceFailureRetainsRequestIdAndWhitelistedCodeWithoutProviderBodyOrCredentials()
    {
        status.set(500);requestId.set("request-prepay-001");
        reply.set("{\"code\":\"SYSTEM_ERROR\",\"message\":\"BE05_PRIVATE_MARKER "+f.apiKey+"\"}");
        QicaiApiException error=assertThrows(QicaiApiException.class,()->f.official.prepay("BE05-SECRET-TRADE",1913,"BE05-SECRET-OPENID","2026-12-31T01:30:00Z"));
        assertEquals(org.springframework.http.HttpStatus.BAD_GATEWAY,error.getStatus());assertNull(error.getCause());
        assertEquals("微信支付服务暂不可用，请查询订单状态后重试",error.getMessage());
        String text=singleDiagnostic();assertTrue(text.contains("op=prepay kind=service_error status=500 code=SYSTEM_ERROR request_id=request-prepay-001 "));
    }

    @Test void unknownCodeAndInvalidRequestIdAreNotCopiedFromUntrustedProviderFields()
    {
        status.set(400);requestId.set("BE05_PRIVATE_MARKER: unsafe value");
        reply.set("{\"code\":\"BE05_PRIVATE_MARKER\",\"message\":\"BE05_PRIVATE_MARKER\"}");
        assertFalse(assertThrows(QicaiApiException.class,()->f.official.prepay("BE05-SECRET-TRADE",1913,"BE05-SECRET-OPENID","2026-12-31T01:30:00Z")) instanceof WechatPrepayRejectedException);
        assertTrue(singleDiagnostic().contains("status=400 code=OTHER request_id=unavailable "));
    }

    @Test void diagnosticsCoverAllSdkOperationsAndKeepKnownBusinessOutcomes()
    {
        status.set(400);requestId.set("request-known-rejection");reply.set("{\"code\":\"NO_AUTH\",\"message\":\"BE05_PRIVATE_MARKER\"}");
        assertThrows(WechatPrepayRejectedException.class,()->f.official.prepay("BE05-SECRET-TRADE",1913,"BE05-SECRET-OPENID","2026-12-31T01:30:00Z"));
        assertTrue(singleDiagnostic().contains("op=prepay kind=explicit_rejection status=400 code=NO_AUTH "));
        diagnosticLogs.list.clear();status.set(404);reply.set("{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"BE05_PRIVATE_MARKER\"}");
        assertNull(f.official.query("BE05-SECRET-TRADE"));assertTrue(singleDiagnostic().contains("op=query kind=not_found status=404 code=ORDER_NOT_EXIST "));
        diagnosticLogs.list.clear();reply.set("{\"code\":\"ORDERPAID\",\"message\":\"BE05_PRIVATE_MARKER\"}");
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,assertThrows(QicaiApiException.class,()->f.official.close("BE05-SECRET-TRADE")).getStatus());
        assertTrue(singleDiagnostic().contains("op=close kind=order_paid status=404 code=ORDERPAID "));
        diagnosticLogs.list.clear();status.set(500);reply.set("{\"code\":\"SYSTEM_ERROR\",\"message\":\"BE05_PRIVATE_MARKER\"}");
        assertThrows(QicaiApiException.class,()->f.official.refund("BE05-SECRET-TRADE","BE05-SECRET-REFUND",1913,"BE05_PRIVATE_MARKER"));
        assertTrue(singleDiagnostic().contains("op=refund kind=service_error status=500 code=SYSTEM_ERROR "));
        diagnosticLogs.list.clear();status.set(404);reply.set("{\"code\":\"RESOURCE_NOT_EXISTS\",\"message\":\"BE05_PRIVATE_MARKER\"}");
        assertNull(f.official.queryRefund("BE05-SECRET-REFUND"));assertTrue(singleDiagnostic().contains("op=query_refund kind=not_found status=404 code=RESOURCE_NOT_EXISTS "));
        diagnosticLogs.list.clear();reply.set("{\"code\":\"ORDER_CLOSED\",\"message\":\"BE05_PRIVATE_MARKER\"}");
        assertDoesNotThrow(()->f.official.close("BE05-SECRET-TRADE"));assertTrue(singleDiagnostic().contains("op=close kind=already_closed status=404 code=ORDER_CLOSED "));
    }

    @Test void invalidSignaturesAndAmbiguousHeadersRemainFailuresWithSafeTrace()
    {
        validSignature.set(false);requestId.set("request-invalid-signature");
        assertFalse(assertThrows(QicaiApiException.class,()->f.official.prepay("QC123",1913,"synthetic-owner","2026-12-31T01:30:00Z")) instanceof WechatPrepayRejectedException);
        assertTrue(singleDiagnostic().contains("kind=response_validation status=200 code=unavailable request_id=request-invalid-signature "));
        diagnosticLogs.list.clear();validSignature.set(true);headerMode.set("duplicate-request-id");
        assertThrows(QicaiApiException.class,()->f.official.prepay("QC123",1913,"synthetic-owner","2026-12-31T01:30:00Z"));
        assertTrue(singleDiagnostic().contains("kind=response_headers_invalid status=200 code=unavailable request_id=unavailable "));
    }

    @Test void timeoutDoesNotReusePreviousCallsStatusCodeOrRequestId()
    {
        status.set(500);reply.set("{\"code\":\"SYSTEM_ERROR\",\"message\":\"BE05_PRIVATE_MARKER\"}");requestId.set("request-previous-call");
        assertThrows(QicaiApiException.class,()->f.official.query("QC123"));assertTrue(singleDiagnostic().contains("request_id=request-previous-call "));
        diagnosticLogs.list.clear();delayMillis.set(5000L);
        QicaiApiException failure=assertThrows(QicaiApiException.class,()->f.official.query("QC124"));
        assertEquals(org.springframework.http.HttpStatus.BAD_GATEWAY,failure.getStatus());assertNull(failure.getCause());
        String text=singleDiagnostic();assertTrue(text.contains("kind=transport_timeout"));
        assertTrue(text.contains("status=0 code=unavailable request_id=unavailable "));assertFalse(text.contains("request-previous-call"));
    }

    @Test void successiveSignedSdkCallsDoNotReuseIdleProviderConnections()
    {
        for(int n=0;n<3;n++)
            assertNotNull(f.official.prepay("QC-TRANSPORT-"+n,1913,"synthetic-owner","2026-12-31T01:30:00Z").get("package"));
        status.set(404);reply.set("{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"synthetic\"}");
        assertNull(f.official.query("QC-TRANSPORT-QUERY"));
        assertEquals(4,providerConnections.size());
        assertEquals(4,new HashSet<>(providerConnections).size(),"Each completed provider connection must be retired instead of reused after idle");
    }

    @Test void unansweredPrepayIsSentOnlyOnceAndRemainsAnUnknownOutcome()
    {
        delayMillis.set(5000L);
        QicaiApiException failure=assertThrows(QicaiApiException.class,()->f.official.prepay("QC-UNKNOWN",1913,"synthetic-owner","2026-12-31T01:30:00Z"));
        assertFalse(failure instanceof WechatPrepayRejectedException);
        assertEquals(1,providerConnections.size(),"Do not automatically replay a financial request after an uncertain response");
        assertTrue(singleDiagnostic().contains("op=prepay kind=transport_timeout"));
    }

    @Test void eofDiagnosticsIdentifyClosedConnectionsWithoutLoggingProviderContent() throws Exception
    {
        Class<?> type=Class.forName(OfficialWechatPaymentGateway.class.getName()+"$DiagnosticAttempt");
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(String.class);constructor.setAccessible(true);
        try(AutoCloseable scope=(AutoCloseable)constructor.newInstance("query"))
        {
            ReflectionTestUtils.invokeMethod(scope,"failure",new java.io.IOException("BE05_PRIVATE_MARKER",new java.io.EOFException("BE05_PRIVATE_MARKER")));
            assertTrue(singleDiagnostic().contains("op=query kind=transport_eof status=0"));
        }
    }

    @Test void concurrentSdkCallsKeepSeparateOperationRequestIdCodeAndCorrelation() throws Exception
    {
        status.set(500);CountDownLatch gate=new CountDownLatch(2);responseGate.set(gate);
        idsByPath.put("/v3/pay/transactions/out-trade-no/QC-DIAG-A","request-concurrent-A");
        codesByPath.put("/v3/pay/transactions/out-trade-no/QC-DIAG-A","SYSTEM_ERROR");
        idsByPath.put("/v3/refund/domestic/refunds/RF-DIAG-B","request-concurrent-B");
        codesByPath.put("/v3/refund/domestic/refunds/RF-DIAG-B","NO_AUTH");
        ExecutorService callers=Executors.newFixedThreadPool(2);
        try
        {
            Future<?> a=callers.submit(()->assertThrows(QicaiApiException.class,()->f.official.query("QC-DIAG-A")));
            Future<?> b=callers.submit(()->assertThrows(QicaiApiException.class,()->f.official.queryRefund("RF-DIAG-B")));
            a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals(0,gate.getCount(),"Both real local requests must overlap at provider gate");
        }
        finally{callers.shutdownNow();}
        List<ILoggingEvent> entries=diagnostics();assertEquals(2,entries.size());
        Set<String> correlations=new HashSet<>();
        for(ILoggingEvent event:entries)
        {
            assertNull(event.getThrowableProxy());String text=event.getFormattedMessage();assertFalse(text.contains("BE05_PRIVATE_MARKER"));
            if(text.contains("op=query "))assertTrue(text.contains("code=SYSTEM_ERROR request_id=request-concurrent-A "));
            else{assertTrue(text.contains("op=query_refund "));assertTrue(text.contains("code=NO_AUTH request_id=request-concurrent-B "));}
            Matcher correlation=Pattern.compile(" correlation=([a-f0-9-]{36}) ").matcher(text);assertTrue(correlation.find());correlations.add(correlation.group(1));
        }
        assertEquals(2,correlations.size());
    }

    @Test void consecutiveTransportAttemptsInOneScopeCannotReuseEarlierResponseMetadata() throws Exception
    {
        // The SDK currently makes one attempt with transport retries disabled.
        // Exercise its real interceptor with consecutive local HTTP attempts in
        // one logical scope, without claiming this is current SDK retry behavior.
        Class<?> scopeType=Class.forName(OfficialWechatPaymentGateway.class.getName()+"$DiagnosticAttempt");
        java.lang.reflect.Constructor<?> constructor=scopeType.getDeclaredConstructor(String.class);constructor.setAccessible(true);
        AutoCloseable scope=(AutoCloseable)constructor.newInstance("query");
        okhttp3.OkHttpClient client=new okhttp3.OkHttpClient.Builder()
                .addInterceptor(OfficialWechatPaymentGateway.responseHeaderNormalizer()).retryOnConnectionFailure(false).build();
        okhttp3.Request request=new okhttp3.Request.Builder().url("http://127.0.0.1:"+server.getAddress().getPort()+"/attempt-test").build();
        try
        {
            status.set(500);requestId.set("request-earlier-attempt");
            try(okhttp3.Response ignored=client.newCall(request).execute()){}
            assertEquals("request-earlier-attempt",ReflectionTestUtils.getField(scope,"requestId"));
            status.set(503);requestId.set(null);
            try(okhttp3.Response ignored=client.newCall(request).execute()){}
            ReflectionTestUtils.invokeMethod(scope,"failure",new java.io.IOException("BE05_PRIVATE_MARKER"));
            assertTrue(singleDiagnostic().contains("status=503 code=unavailable request_id=unavailable "));
            diagnosticLogs.list.clear();
            requestId.set("BE05_PRIVATE_MARKER: invalid");
            try(okhttp3.Response ignored=client.newCall(request).execute()){}
            assertEquals("unavailable",ReflectionTestUtils.getField(scope,"requestId"));
            headerMode.set("duplicate-request-id");requestId.set("request-before-duplicate");
            assertThrows(java.io.IOException.class,()->client.newCall(request).execute());
            assertEquals(Boolean.TRUE,ReflectionTestUtils.getField(scope,"invalidHeaders"));
            okhttp3.OkHttpClient timeoutClient=client.newBuilder().addInterceptor(chain->{throw new java.net.SocketTimeoutException("BE05_PRIVATE_MARKER");}).build();
            java.io.IOException timeout=assertThrows(java.io.IOException.class,()->timeoutClient.newCall(request).execute());
            ReflectionTestUtils.invokeMethod(scope,"failure",timeout);
            String text=singleDiagnostic();assertTrue(text.contains("kind=transport_timeout status=0 code=unavailable request_id=unavailable "));
            assertFalse(text.contains("request-earlier-attempt"));assertEquals(Boolean.FALSE,ReflectionTestUtils.getField(scope,"invalidHeaders"));
        }
        finally{scope.close();}
        assertNull(((ThreadLocal<?>)ReflectionTestUtils.getField(OfficialWechatPaymentGateway.class,"diagnosticContext")).get());
    }

}

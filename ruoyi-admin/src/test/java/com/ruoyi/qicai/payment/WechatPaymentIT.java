package com.ruoyi.qicai.payment;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.qicai.QicaiApiException;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.google.gson.Gson;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP/filter/DB transactions with a synthetic provider ONLY. Run via payment-acceptance.py. */
@SpringBootTest(classes=RuoYiApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"qicai.wechat-pay.maintenance-enabled=false","server.address=127.0.0.1","qicai.wechat-pay.enabled=false","spring.devtools.restart.enabled=false"})
@ActiveProfiles("qicai")
class WechatPaymentIT
{
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired WechatPayProperties config;
    @Autowired BCryptPasswordEncoder passwords;
    @Autowired WechatPaymentService payments;
    @Autowired WechatRefundService refunds;
    @Autowired com.ruoyi.qicai.QicaiService qicai;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @org.springframework.boot.web.server.LocalServerPort int port;
    @MockBean WechatPaymentGateway provider;
    static final AtomicInteger sequence=new AtomicInteger(1000);
    PaymentCryptoFixture crypto;
    long customer, product, courier;
    String customerToken,adminToken,openId;
    List<String> trades;
    Map<String,Transaction> remote;
    static final List<Map<String,Object>> endpoints=Collections.synchronizedList(new ArrayList<Map<String,Object>>());
    @AfterAll static void saveEndpoints() throws Exception
    {
        String out=System.getenv("QICAI_EVIDENCE_DIR");assertNotNull(out);
        java.nio.file.Files.write(java.nio.file.Paths.get(out,"payment-api-endpoints.json"),new ObjectMapper().writeValueAsBytes(map("kind","real local HTTP; synthetic provider", "endpoints",endpoints)));
    }
    @AfterEach void resetDisabledFixtures()
    {
        jdbc.update("DELETE s FROM qc_session s JOIN qc_customer c ON s.principal_type='customer' AND c.id=s.principal_id WHERE c.id=? AND (c.deleted=1 OR c.status='DISABLED')",customer);
    }

    @BeforeEach void isolatedFixtures() throws Exception
    {
        // Do not rely on a user-controlled environment flag alone.
        assertEquals("1",System.getenv("QICAI_ISOLATED_TEST"));
        assertEquals(13319,jdbc.queryForObject("SELECT @@port",Integer.class));
        assertEquals("qicai_acceptance",jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(java.nio.file.Paths.get(System.getenv("QICAI_RUNTIME"),"uploads").toAbsolutePath().normalize(),
                java.nio.file.Paths.get(com.ruoyi.common.config.RuoYiConfig.getProfile()).toAbsolutePath().normalize());
        crypto=new PaymentCryptoFixture();config.setEnabled(true);config.setAppId(crypto.properties.getAppId());config.setMerchantId(crypto.properties.getMerchantId());
        int n=sequence.incrementAndGet();String phone=String.format("13977%06d",n);
        jdbc.update("INSERT INTO qc_customer(audience,phone,password_hash,contact_name,address,status) VALUES('consumer',?,?,?,'synthetic-address','ACTIVE')",phone,passwords.encode("QcPayment2026!"),"synthetic-buyer");
        customer=jdbc.queryForObject("SELECT id FROM qc_customer WHERE audience='consumer' AND phone=?",Long.class,phone);
        jdbc.update("INSERT INTO qc_product(sku,name,category,unit,stock,business_price,consumer_price,status) VALUES(?,?,?,'件',100,20.00,24.68,'ACTIVE')","PAY-"+n,"synthetic-pay-product","synthetic");
        product=jdbc.queryForObject("SELECT id FROM qc_product WHERE sku=?",Long.class,"PAY-"+n);
        jdbc.update("INSERT INTO qc_courier(phone,password_hash,name,vehicle_no,status,available) VALUES(?,?,'synthetic-courier','TEST','ACTIVE',1)",String.format("13877%06d",n),passwords.encode("QcPayment2026!"));
        courier=jdbc.queryForObject("SELECT id FROM qc_courier WHERE phone=?",Long.class,String.format("13877%06d",n));
        customerToken=call(HttpMethod.POST,"/qicai/mobile/auth/login",map("audience","consumer","phone",phone,"password","QcPayment2026!"),null,false).getBody().path("data").path("token").asText();
        adminToken=call(HttpMethod.POST,"/login",map("username","admin","password",System.getenv("QICAI_ADMIN_PASSWORD")),null,false).getBody().path("token").asText();
        assertFalse(customerToken.isEmpty());assertFalse(adminToken.isEmpty());
        openId="synthetic-openid-"+customer;trades=Collections.synchronizedList(new ArrayList<String>());remote=new ConcurrentHashMap<String,Transaction>();
        when(provider.exchangeCode(anyString())).thenAnswer(a->((String)a.getArgument(0)).startsWith("same-wechat-")?"synthetic-shared":openId);
        when(provider.prepay(anyString(),anyInt(),anyString(),anyString())).thenAnswer(a->{trades.add(a.getArgument(0));return map("timeStamp","1","nonceStr","synthetic","package","prepay_id=synthetic","signType","RSA","paySign","synthetic");});
        when(provider.query(anyString())).thenAnswer(a->remote.get(a.getArgument(0)));
        when(provider.parseNotification(anyString(),any())).thenAnswer(a->crypto.official.parseNotification(a.getArgument(0),a.getArgument(1)));
    }
    static Map<String,Object> map(Object... kv) { Map<String,Object> result=new LinkedHashMap<String,Object>();for(int i=0;i<kv.length;i+=2)result.put((String)kv[i],kv[i+1]);return result; }
    ResponseEntity<JsonNode> call(HttpMethod method,String path,Object body,String token,boolean admin)
    {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Qicai-Privacy-Accepted","1");headers.set("X-Qicai-Privacy-Version",com.ruoyi.qicai.QicaiPrivacyService.VERSION);
        if(token!=null)headers.set(admin?"Authorization":"X-Qicai-Token",admin?"Bearer "+token:token);
        ResponseEntity<JsonNode> result=http.exchange(path,method,new HttpEntity<Object>(body,headers),JsonNode.class);
        endpoints.add(map("method",method.name(),"path",path,"status",result.getStatusCodeValue()));return result;
    }
    long order() { return order(null); }
    long order(Long coupon)
    {
        Map<String,Object> body=map("productId",product,"quantity",2,"idempotencyKey",UUID.randomUUID().toString(),"deliveryAddress","synthetic-address");
        if(coupon!=null)body.put("customerCouponId",coupon);
        ResponseEntity<JsonNode> result=call(HttpMethod.POST,"/qicai/mobile/orders",body,customerToken,false);
        assertEquals(201,result.getStatusCodeValue());return result.getBody().path("data").path("id").asLong();
    }
    void bind() { assertEquals(200,call(HttpMethod.POST,"/qicai/mobile/payments/wechat/identity",map("code","fresh-code-"+UUID.randomUUID()),customerToken,false).getStatusCodeValue()); }
    ResponseEntity<JsonNode> prepay(long id) { return call(HttpMethod.POST,"/qicai/mobile/orders/"+id+"/wechat-prepay",map("amount",1,"openid","forged"),customerToken,false); }
    ResponseEntity<JsonNode> cancel(long id) { return call(HttpMethod.POST,"/qicai/admin/orders/"+id+"/cancel",map(),adminToken,true); }
    Map<String,Object> state(long id) { return jdbc.queryForMap("SELECT status,payment_status,version FROM qc_order WHERE id=?",id); }
    String trade(long id) { return jdbc.queryForObject("SELECT order_no FROM qc_order WHERE id=?",String.class,id); }
    int stock() { return jdbc.queryForObject("SELECT stock FROM qc_product WHERE id=?",Integer.class,product); }
    ResponseEntity<String> notification(Map<String,Object> transaction,boolean tamper) throws Exception
    {
        String body=crypto.envelope(transaction);MockHttpServletRequest signed=crypto.signed(body);
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        for(String key:Collections.list(signed.getHeaderNames()))headers.set(key,signed.getHeader(key));
        ResponseEntity<String> result=http.exchange("/qicai/payment/wechat/notify",HttpMethod.POST,new HttpEntity<byte[]>((body+(tamper?" ":"")).getBytes(StandardCharsets.UTF_8),headers),String.class);
        endpoints.add(map("method","POST","path","/qicai/payment/wechat/notify","status",result.getStatusCodeValue()));return result;
    }

    @Test void newOrdersAreUnpaidAndCannotBeAssigned() throws Exception
    {
        long id=order();assertEquals("UNPAID",state(id).get("payment_status"));assertEquals("PENDING_PAYMENT",state(id).get("status"));assertEquals(98,stock());
        ResponseEntity<JsonNode> result=call(HttpMethod.POST,"/qicai/admin/deliveries/assign",map("orderId",id,"courierId",courier,"warehouseName","synthetic","warehouseAddress","synthetic","distanceKm",1),adminToken,true);
        assertEquals(409,result.getStatusCodeValue());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qc_delivery_task WHERE order_id=?",Integer.class,id));
        assertEquals(401,call(HttpMethod.POST,"/qicai/mobile/orders/"+id+"/wechat-prepay",map(),null,false).getStatusCodeValue());
    }
    @Test void trustedIdentityAuthoritativeAmountAndStableRetries() throws Exception
    {
        long id=order();assertEquals(409,prepay(id).getStatusCodeValue());
        String key=jdbc.queryForObject("SELECT idempotency_key FROM qc_order WHERE id=?",String.class,id);
        assertEquals(id,call(HttpMethod.POST,"/qicai/mobile/orders",map("productId",99999999,"quantity",99,"customerCouponId",99999999,"deliveryAddress","changed-address","idempotencyKey",key),customerToken,false).getBody().path("data").path("id").asLong());
        assertEquals(new java.math.BigDecimal("49.36"),jdbc.queryForObject("SELECT payable_amount FROM qc_order WHERE id=?",java.math.BigDecimal.class,id));
        assertEquals("synthetic-address",jdbc.queryForObject("SELECT delivery_address FROM qc_order WHERE id=?",String.class,id));assertEquals(98,stock());
        assertEquals(400,call(HttpMethod.POST,"/qicai/mobile/payments/wechat/identity",map("code","fresh","openid","forged"),customerToken,false).getStatusCodeValue());
        bind();assertEquals(200,prepay(id).getStatusCodeValue());assertEquals(200,prepay(id).getStatusCodeValue());
        assertEquals(Arrays.asList(trade(id),trade(id)),trades);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_payment WHERE order_id=?",Integer.class,id));
        assertEquals(4936,jdbc.queryForObject("SELECT amount_fen FROM qc_payment WHERE order_id=?",Integer.class,id));
        assertEquals(openId,jdbc.queryForObject("SELECT payer_open_id FROM qc_payment WHERE order_id=?",String.class,id));verify(provider,times(2)).prepay(eq(trade(id)),eq(4936),eq(openId),anyString());assertEquals(98,stock());
    }
    @Test void realSignedMultilineCallbackIsPersistedAndDuplicateCannotReverseFulfilment() throws Exception
    {
        long id=order();bind();prepay(id);Map<String,Object> body=crypto.transaction(trade(id),4936,openId);
        assertEquals(204,notification(body,false).getStatusCodeValue());assertEquals("PAID",state(id).get("payment_status"));assertEquals("PENDING_SHIPMENT",state(id).get("status"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_order_notice WHERE order_id=? AND event_type='PAID' AND recipient_type='customer'",Integer.class,id));
        jdbc.update("UPDATE qc_order SET status='COMPLETED' WHERE id=?",id);Map<String,Object> before=state(id);
        assertEquals(204,notification(body,false).getStatusCodeValue());assertEquals(before,state(id));assertEquals(98,stock());
        assertEquals(409,cancel(id).getStatusCodeValue());
        // No customer session is required to preserve a genuine collection event.
        jdbc.update("UPDATE qc_customer SET status='DISABLED',deleted=1 WHERE id=?",customer);
        assertEquals(204,notification(body,false).getStatusCodeValue());assertEquals(before,state(id));
    }
    @Test void badSignatureAndSemanticMismatchesNeverMarkPaid() throws Exception
    {
        long id=order();bind();prepay(id);Map<String,Object> valid=crypto.transaction(trade(id),4936,openId);Map<String,Object> before=state(id);
        assertEquals(401,notification(valid,true).getStatusCodeValue());
        for(String field:Arrays.asList("mchid","appid","out_trade_no"))
        {
            Map<String,Object> wrong=new LinkedHashMap<String,Object>(valid);wrong.put(field,"wrong-value");int status=notification(wrong,false).getStatusCodeValue();assertTrue(status==409||status==404);assertEquals(before,state(id));
        }
        Map<String,Object> wrongAmount=crypto.transaction(trade(id),1,openId);assertEquals(409,notification(wrongAmount,false).getStatusCodeValue());
        Map<String,Object> wrongPayer=crypto.transaction(trade(id),4936,"cross-user");assertEquals(409,notification(wrongPayer,false).getStatusCodeValue());
        Map<String,Object> wrongCurrency=crypto.transaction(trade(id),4936,openId);((Map<String,Object>)wrongCurrency.get("amount")).put("currency","USD");assertEquals(409,notification(wrongCurrency,false).getStatusCodeValue());
        assertEquals(before,state(id));assertEquals(98,stock());
    }
    @Test void queryRecoversLostNotificationAndPaidCancelIsRefused() throws Exception
    {
        long id=order();bind();prepay(id);remote.put(trade(id),new Gson().fromJson(json.writeValueAsString(crypto.transaction(trade(id),4936,openId)),Transaction.class));
        ResponseEntity<JsonNode> result=call(HttpMethod.GET,"/qicai/mobile/orders/"+id+"/payment",null,customerToken,false);
        assertEquals("PAID",result.getBody().path("data").path("paymentStatus").asText());assertEquals("PAID",state(id).get("payment_status"));
        assertEquals(409,cancel(id).getStatusCodeValue());assertEquals(98,stock());
        assertEquals(200,call(HttpMethod.POST,"/qicai/admin/deliveries/assign",map("orderId",id,"courierId",courier,"warehouseName","synthetic","warehouseAddress","synthetic","distanceKm",1),adminToken,true).getStatusCodeValue());
    }
    @Test void unknownPrepayAndFailedCloseRetainResourcesThenConfirmedCloseReleasesOnce() throws Exception
    {
        long id=order();bind();when(provider.prepay(anyString(),anyInt(),anyString(),anyString())).thenThrow(new QicaiApiException(HttpStatus.BAD_GATEWAY,"synthetic-timeout"));
        assertEquals(502,prepay(id).getStatusCodeValue());assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM qc_payment WHERE order_id=?",String.class,id));
        doThrow(new QicaiApiException(HttpStatus.BAD_GATEWAY,"synthetic-close-unknown")).when(provider).close(trade(id));
        assertEquals(502,cancel(id).getStatusCodeValue());assertEquals(98,stock());assertEquals("UNPAID",state(id).get("payment_status"));
        doNothing().when(provider).close(trade(id));assertEquals(200,cancel(id).getStatusCodeValue());assertEquals(100,stock());
        assertEquals("CLOSED",state(id).get("payment_status"));assertEquals("CANCELLED",state(id).get("status"));assertEquals(200,cancel(id).getStatusCodeValue());assertEquals(100,stock());
        assertEquals(409,notification(crypto.transaction(trade(id),4936,openId),false).getStatusCodeValue());
    }
    @Test void preparedButNeverSentAndCouponCanBeCancelledWithoutProvider() throws Exception
    {
        String name="synthetic-pay-coupon-"+customer;
        jdbc.update("INSERT INTO qc_coupon(name,audience,threshold_amount,discount_amount,valid_from,valid_to,status) VALUES(?,'all',0,5.55,DATE_SUB(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 1 DAY),'ACTIVE')",name);
        long coupon=jdbc.queryForObject("SELECT id FROM qc_coupon WHERE name=?",Long.class,name);jdbc.update("INSERT INTO qc_customer_coupon(customer_id,coupon_id) VALUES(?,?)",customer,coupon);
        long claim=jdbc.queryForObject("SELECT id FROM qc_customer_coupon WHERE customer_id=? AND coupon_id=?",Long.class,customer,coupon);
        long id=order(claim);
        jdbc.update("INSERT INTO qc_payment(order_id,out_trade_no,merchant_id,app_id,payer_open_id,amount_fen) VALUES(?,?,?,?,?,?)",id,trade(id),config.getMerchantId(),config.getAppId(),openId,4381);
        assertEquals("PREPARED",jdbc.queryForObject("SELECT status FROM qc_payment WHERE order_id=?",String.class,id));
        assertEquals(200,cancel(id).getStatusCodeValue());verify(provider,never()).close(anyString());assertEquals(100,stock());
        assertEquals("AVAILABLE",jdbc.queryForObject("SELECT status FROM qc_customer_coupon WHERE id=?",String.class,claim));
        assertEquals(409,prepay(id).getStatusCodeValue());
    }
    @Test void remoteClosedQueryPersistsCancellationAndReleasesOnce() throws Exception
    {
        long id=order();bind();prepay(id);Map<String,Object> t=crypto.transaction(trade(id),4936,openId);t.put("trade_state","CLOSED");remote.put(trade(id),new Gson().fromJson(json.writeValueAsString(t),Transaction.class));
        assertEquals("CLOSED",call(HttpMethod.GET,"/qicai/mobile/orders/"+id+"/payment",null,customerToken,false).getBody().path("data").path("paymentStatus").asText());
        assertEquals("CANCELLED",state(id).get("status"));assertEquals(100,stock());
        call(HttpMethod.GET,"/qicai/mobile/orders/"+id+"/payment",null,customerToken,false);assertEquals(100,stock());
    }
    @Test void crossUserAndPendingCustomerCannotPayOrBind() throws Exception
    {
        long id=order();jdbc.update("INSERT INTO qc_customer(audience,phone,password_hash,contact_name,address,status) VALUES('consumer','13977009999',?,'synthetic-other','synthetic','ACTIVE')",passwords.encode("QcPayment2026!"));
        String other=call(HttpMethod.POST,"/qicai/mobile/auth/login",map("audience","consumer","phone","13977009999","password","QcPayment2026!"),null,false).getBody().path("data").path("token").asText();
        assertEquals(404,call(HttpMethod.GET,"/qicai/mobile/orders/"+id+"/payment",null,other,false).getStatusCodeValue());
        assertEquals(404,call(HttpMethod.POST,"/qicai/mobile/orders/"+id+"/wechat-prepay",map(),other,false).getStatusCodeValue());
        jdbc.update("UPDATE qc_customer SET status='PENDING' WHERE id=?",customer);
        assertEquals(403,call(HttpMethod.POST,"/qicai/mobile/payments/wechat/identity",map("code","fresh-"+UUID.randomUUID()),customerToken,false).getStatusCodeValue());assertEquals(403,prepay(id).getStatusCodeValue());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qc_payment WHERE order_id=?",Integer.class,id));
    }
    @Test void simultaneousCallbackAndCancellationKeepMoneyAndStockConsistent() throws Exception
    {
        long id=order();bind();prepay(id);Map<String,Object> t=crypto.transaction(trade(id),4936,openId);
        // Model the provider's mutually exclusive paid-vs-closed decision, never a fake HTTP success.
        doThrow(new QicaiApiException(HttpStatus.CONFLICT,"synthetic-order-paid")).when(provider).close(trade(id));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try { Future<Integer> a=pool.submit(()->notification(t,false).getStatusCodeValue());Future<Integer>b=pool.submit(()->cancel(id).getStatusCodeValue());assertEquals(204,a.get(15,TimeUnit.SECONDS));assertEquals(409,b.get(15,TimeUnit.SECONDS)); }
        finally {pool.shutdownNow();}
        assertEquals("PAID",state(id).get("payment_status"));assertEquals(98,stock());
    }
    @Test void zeroAmountLegacyAndOverflowAreExplicit() throws Exception
    {
        config.setEnabled(false);long legacy=order();assertEquals("NOT_REQUIRED",state(legacy).get("payment_status"));assertEquals("PENDING_SHIPMENT",state(legacy).get("status"));
        config.setEnabled(true);
        String name="synthetic-full-coupon-"+customer;jdbc.update("INSERT INTO qc_coupon(name,audience,threshold_amount,discount_amount,valid_from,valid_to,status) VALUES(?,'all',0,100,DATE_SUB(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 1 DAY),'ACTIVE')",name);
        long coupon=jdbc.queryForObject("SELECT id FROM qc_coupon WHERE name=?",Long.class,name);jdbc.update("INSERT INTO qc_customer_coupon(customer_id,coupon_id) VALUES(?,?)",customer,coupon);
        long claim=jdbc.queryForObject("SELECT id FROM qc_customer_coupon WHERE customer_id=? AND coupon_id=?",Long.class,customer,coupon);
        long zero=order(claim);assertEquals(new java.math.BigDecimal("0.00"),jdbc.queryForObject("SELECT payable_amount FROM qc_order WHERE id=?",java.math.BigDecimal.class,zero));assertEquals("NO_PAYMENT_REQUIRED",state(zero).get("payment_status"));assertEquals("PENDING_SHIPMENT",state(zero).get("status"));assertEquals(409,prepay(zero).getStatusCodeValue());
        jdbc.update("UPDATE qc_product SET consumer_price=21474836.48 WHERE id=?",product);int before=stock();
        ResponseEntity<JsonNode> over=call(HttpMethod.POST,"/qicai/mobile/orders",map("productId",product,"quantity",1,"idempotencyKey",UUID.randomUUID().toString(),"deliveryAddress","synthetic"),customerToken,false);
        assertEquals(400,over.getStatusCodeValue());assertEquals(before,stock());assertEquals("NOT_REQUIRED",state(legacy).get("payment_status"));verify(provider,never()).prepay(anyString(),anyInt(),anyString(),anyString());
    }
    @Test void callbackMethodsAndOversizeBodyAreBounded() throws Exception
    {
        assertEquals(401,http.getForEntity("/qicai/payment/wechat/notify",String.class).getStatusCodeValue());
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        assertEquals(413,http.exchange("/qicai/payment/wechat/notify",HttpMethod.POST,new HttpEntity<byte[]>(new byte[65537],headers),String.class).getStatusCodeValue());
        verify(provider,never()).parseNotification(anyString(),any());
    }
    @Test void actualChunkedUnicodeEncodedPathAndBoundaryAreBounded() throws Exception
    {
        java.net.http.HttpClient client=java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build();
        for(String suffix:Arrays.asList("notify","%6eotify"))
        {
            java.net.http.HttpRequest request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/qicai/payment/wechat/"+suffix))
                .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(new byte[65537]))).build();
            int response=client.send(request,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();assertTrue(response==413||response==400);
        }
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        byte[] unicode=new String(new char[21846]).replace('\0','汉').getBytes(StandardCharsets.UTF_8);
        assertEquals(413,http.exchange("/qicai/payment/wechat/notify",HttpMethod.POST,new HttpEntity<byte[]>(unicode,headers),String.class).getStatusCodeValue());
        assertEquals(401,http.exchange("/qicai/payment/wechat/notify",HttpMethod.POST,new HttpEntity<byte[]>(new byte[65536],headers),String.class).getStatusCodeValue());
    }
    @Test void explicitRejectionReleasesButEarlierUnknownOutcomeIsPreserved() throws Exception
    {
        long id=order();bind();when(provider.prepay(anyString(),anyInt(),anyString(),anyString())).thenThrow(new WechatPrepayRejectedException());
        assertEquals(502,prepay(id).getStatusCodeValue());assertEquals("REJECTED",jdbc.queryForObject("SELECT status FROM qc_payment WHERE order_id=?",String.class,id));
        config.setEnabled(false);assertEquals(200,cancel(id).getStatusCodeValue());verify(provider,never()).close(anyString());assertEquals(100,stock());
        config.setEnabled(true);long other=order();doThrow(new QicaiApiException(HttpStatus.BAD_GATEWAY,"synthetic-unknown")).when(provider).prepay(anyString(),anyInt(),anyString(),anyString());assertEquals(502,prepay(other).getStatusCodeValue());
        doThrow(new WechatPrepayRejectedException()).when(provider).prepay(anyString(),anyInt(),anyString(),anyString());assertEquals(502,prepay(other).getStatusCodeValue());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM qc_payment WHERE order_id=?",String.class,other));
        assertEquals(1,jdbc.queryForObject("SELECT uncertain_attempts FROM qc_payment WHERE order_id=?",Integer.class,other));
        doThrow(new QicaiApiException(HttpStatus.BAD_GATEWAY,"synthetic-unknown-close")).when(provider).close(trade(other));assertEquals(502,cancel(other).getStatusCodeValue());assertEquals(98,stock());
    }
    @Test void anonymousExpiredCourierAndBackendSessionsCannotUseMobilePayment() throws Exception
    {
        long id=order();
        String phone=jdbc.queryForObject("SELECT phone FROM qc_courier WHERE id=?",String.class,courier);
        String driver=call(HttpMethod.POST,"/qicai/mobile/courier/login",map("phone",phone,"password","QcPayment2026!"),null,false).getBody().path("data").path("token").asText();assertFalse(driver.isEmpty());
        for(String token:Arrays.asList((String)null,driver)) {
            int expected=token==null?401:403;
            assertEquals(expected,call(HttpMethod.POST,"/qicai/mobile/payments/wechat/identity",map("code","fresh-"+UUID.randomUUID()),token,false).getStatusCodeValue());
            assertEquals(expected,call(HttpMethod.POST,"/qicai/mobile/orders/"+id+"/wechat-prepay",map(),token,false).getStatusCodeValue());
            assertEquals(expected,call(HttpMethod.GET,"/qicai/mobile/orders/"+id+"/payment",null,token,false).getStatusCodeValue());
        }
        assertEquals(401,call(HttpMethod.POST,"/qicai/mobile/orders/"+id+"/wechat-prepay",map(),adminToken,true).getStatusCodeValue());
        assertEquals(400,call(HttpMethod.POST,"/qicai/mobile/payments/wechat/identity",map("code"," "),customerToken,false).getStatusCodeValue());
        jdbc.update("UPDATE qc_session SET expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE principal_type='customer' AND principal_id=?",customer);
        assertEquals(401,call(HttpMethod.POST,"/qicai/mobile/payments/wechat/identity",map("code","fresh-"+UUID.randomUUID()),customerToken,false).getStatusCodeValue());
        assertEquals(401,prepay(id).getStatusCodeValue());
        assertEquals(401,call(HttpMethod.GET,"/qicai/mobile/orders/"+id+"/payment",null,customerToken,false).getStatusCodeValue());
        verify(provider,never()).exchangeCode(anyString());verify(provider,never()).prepay(anyString(),anyInt(),anyString(),anyString());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qc_payment WHERE order_id=?",Integer.class,id));assertEquals("UNPAID",state(id).get("payment_status"));assertEquals(98,stock());
    }
    void betweenTransactions(Runnable change,Runnable assertion)
    {
        org.springframework.transaction.support.TransactionTemplate original=(org.springframework.transaction.support.TransactionTemplate)org.springframework.test.util.ReflectionTestUtils.getField(payments,"tx");
        AtomicInteger calls=new AtomicInteger();
        org.springframework.transaction.support.TransactionTemplate observed=new org.springframework.transaction.support.TransactionTemplate(transactionManager){
            @Override public <T>T execute(org.springframework.transaction.support.TransactionCallback<T> action){T result=original.execute(action);if(calls.incrementAndGet()==1)change.run();return result;}};
        org.springframework.test.util.ReflectionTestUtils.setField(payments,"tx",observed);
        try{assertion.run();}finally{org.springframework.test.util.ReflectionTestUtils.setField(payments,"tx",original);}
    }
    @Test void disableBetweenTransactionsCannotSendAndPreparedCancelIgnoresChangedConfiguration() throws Exception
    {
        long id=order();bind();betweenTransactions(()->jdbc.update("UPDATE qc_customer SET status='DISABLED' WHERE id=?",customer),()->assertEquals(403,prepay(id).getStatusCodeValue()));
        verify(provider,never()).prepay(anyString(),anyInt(),anyString(),anyString());assertEquals("PREPARED",jdbc.queryForObject("SELECT status FROM qc_payment WHERE order_id=?",String.class,id));
        config.setEnabled(false);config.setMerchantId("1999999999");assertEquals(200,cancel(id).getStatusCodeValue());assertEquals(100,stock());verify(provider,never()).close(anyString());
    }
    @Test void configurationMismatchBeforeSendRetainsPreparedState() throws Exception
    {
        long id=order();bind();betweenTransactions(()->config.setMerchantId("1999999999"),()->assertEquals(409,prepay(id).getStatusCodeValue()));
        assertEquals("PREPARED",jdbc.queryForObject("SELECT status FROM qc_payment WHERE order_id=?",String.class,id));assertEquals(0,jdbc.queryForObject("SELECT uncertain_attempts FROM qc_payment WHERE order_id=?",Integer.class,id));
        config.setEnabled(false);assertEquals(200,cancel(id).getStatusCodeValue());assertEquals(100,stock());
    }
    void awaitOrderLockWait() throws Exception
    {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline){if(jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='qc_order'",Integer.class)>0)return;Thread.sleep(20);}
        fail("No actual order lock wait observed");
    }
    @Test void prepayVersusCancelActuallyWaitsForOrderLockAndClosesBeforeReleasing() throws Exception
    {
        long id=order();bind();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(provider.prepay(anyString(),anyInt(),anyString(),anyString())).thenAnswer(a->{entered.countDown();if(!release.await(12,TimeUnit.SECONDS))throw new AssertionError("prepay gate timed out");return map("package","prepay_id=synthetic");});
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Future<Integer> a=pool.submit(()->prepay(id).getStatusCodeValue());assertTrue(entered.await(8,TimeUnit.SECONDS));Future<Integer>b=pool.submit(()->cancel(id).getStatusCodeValue());awaitOrderLockWait();verify(provider,never()).close(anyString());assertEquals(98,stock());release.countDown();assertEquals(200,a.get(12,TimeUnit.SECONDS));assertEquals(200,b.get(12,TimeUnit.SECONDS));}
        finally{release.countDown();pool.shutdownNow();}
        verify(provider).close(trade(id));assertEquals(100,stock());assertEquals("CANCELLED",state(id).get("status"));assertEquals(409,prepay(id).getStatusCodeValue());
    }
    @Test void duplicateTransactionAcrossOrdersRollsBackSecondCollection() throws Exception
    {
        long a=order(),b=order();bind();prepay(a);prepay(b);Map<String,Object> first=crypto.transaction(trade(a),4936,openId),second=crypto.transaction(trade(b),4936,openId);second.put("transaction_id",first.get("transaction_id"));
        assertEquals(204,notification(first,false).getStatusCodeValue());assertEquals(409,notification(second,false).getStatusCodeValue());assertEquals("UNPAID",state(b).get("payment_status"));assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM qc_payment WHERE order_id=?",String.class,b));assertEquals(96,stock());
    }
    @Test void sameWechatCanPayBothAudiencesWithoutCrossOrderAccess() throws Exception
    {
        String phone=jdbc.queryForObject("SELECT phone FROM qc_customer WHERE id=?",String.class,customer);
        jdbc.update("INSERT INTO qc_customer(audience,phone,password_hash,company_name,contact_name,tax_id,address,status) VALUES('business',?,?,'synthetic-business','synthetic',?,'synthetic','ACTIVE')",phone,passwords.encode("QcPayment2026!"),"91310000"+String.format("%08d",customer));
        long business=jdbc.queryForObject("SELECT id FROM qc_customer WHERE audience='business' AND phone=?",Long.class,phone);
        String bt=call(HttpMethod.POST,"/qicai/mobile/auth/login",map("audience","business","phone",phone,"password","QcPayment2026!"),null,false).getBody().path("data").path("token").asText();
        assertEquals(200,call(HttpMethod.POST,"/qicai/mobile/payments/wechat/identity",map("code","same-wechat-"+UUID.randomUUID()),customerToken,false).getStatusCodeValue());assertEquals(200,call(HttpMethod.POST,"/qicai/mobile/payments/wechat/identity",map("code","same-wechat-"+UUID.randomUUID()),bt,false).getStatusCodeValue());
        long a=order();long b=call(HttpMethod.POST,"/qicai/mobile/orders",map("productId",product,"quantity",2,"idempotencyKey",UUID.randomUUID().toString(),"deliveryAddress","synthetic"),bt,false).getBody().path("data").path("id").asLong();
        assertEquals(200,prepay(a).getStatusCodeValue());assertEquals(200,call(HttpMethod.POST,"/qicai/mobile/orders/"+b+"/wechat-prepay",map(),bt,false).getStatusCodeValue());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM qc_wechat_identity WHERE customer_id IN (?,?) AND open_id='synthetic-shared'",Integer.class,customer,business));
        assertEquals(4000,jdbc.queryForObject("SELECT amount_fen FROM qc_payment WHERE order_id=?",Integer.class,b));assertEquals(404,call(HttpMethod.GET,"/qicai/mobile/orders/"+a+"/payment",null,bt,false).getStatusCodeValue());
    }
    @Test void twoKnownRejectionsInterleaveAndResolveWithoutErasingUnknownOutcomes() throws Exception
    {
        long id=order();bind();when(provider.prepay(anyString(),anyInt(),anyString(),anyString())).thenThrow(new WechatPrepayRejectedException());
        CountDownLatch firstRejected=new CountDownLatch(1),secondRejected=new CountDownLatch(1);AtomicInteger rejects=new AtomicInteger();
        org.springframework.transaction.support.TransactionTemplate original=(org.springframework.transaction.support.TransactionTemplate)org.springframework.test.util.ReflectionTestUtils.getField(payments,"tx");
        org.springframework.transaction.support.TransactionTemplate observed=new org.springframework.transaction.support.TransactionTemplate(transactionManager){
            @Override public <T>T execute(org.springframework.transaction.support.TransactionCallback<T> action){try{return original.execute(action);}catch(WechatPrepayRejectedException ex){
                if(rejects.incrementAndGet()==1){firstRejected.countDown();try{if(!secondRejected.await(10,TimeUnit.SECONDS))throw new AssertionError("second rejection never interleaved");}catch(InterruptedException e){throw new AssertionError(e);}}
                else secondRejected.countDown();throw ex;}}};
        org.springframework.test.util.ReflectionTestUtils.setField(payments,"tx",observed);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Future<Integer>a=pool.submit(()->prepay(id).getStatusCodeValue());assertTrue(firstRejected.await(8,TimeUnit.SECONDS));Future<Integer>b=pool.submit(()->prepay(id).getStatusCodeValue());assertEquals(502,a.get(12,TimeUnit.SECONDS));assertEquals(502,b.get(12,TimeUnit.SECONDS));}
        finally{secondRejected.countDown();pool.shutdownNow();org.springframework.test.util.ReflectionTestUtils.setField(payments,"tx",original);}
        assertEquals("REJECTED",jdbc.queryForObject("SELECT status FROM qc_payment WHERE order_id=?",String.class,id));assertEquals(0,jdbc.queryForObject("SELECT uncertain_attempts FROM qc_payment WHERE order_id=?",Integer.class,id));
        assertEquals(200,cancel(id).getStatusCodeValue());assertEquals(100,stock());verify(provider,never()).close(anyString());
    }
    @Test void signedCallbackActuallyWaitsForPrepayLockThenAppliesOnce() throws Exception
    {
        long id=order();bind();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(provider.prepay(anyString(),anyInt(),anyString(),anyString())).thenAnswer(a->{entered.countDown();if(!release.await(12,TimeUnit.SECONDS))throw new AssertionError("prepay gate timed out");return map("package","prepay_id=synthetic");});
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Future<Integer>a=pool.submit(()->prepay(id).getStatusCodeValue());assertTrue(entered.await(8,TimeUnit.SECONDS));Future<Integer>b=pool.submit(()->notification(crypto.transaction(trade(id),4936,openId),false).getStatusCodeValue());awaitOrderLockWait();assertEquals("UNPAID",state(id).get("payment_status"));release.countDown();assertEquals(200,a.get(12,TimeUnit.SECONDS));assertEquals(204,b.get(12,TimeUnit.SECONDS));}
        finally{release.countDown();pool.shutdownNow();}
        assertEquals("PAID",state(id).get("payment_status"));assertEquals(98,stock());assertEquals(409,cancel(id).getStatusCodeValue());
    }
    @Test void databaseFailureRollsBackPaymentAndOrderThenCallbackRetrySucceeds() throws Exception
    {
        long id=order();bind();prepay(id);Map<String,Object> body=crypto.transaction(trade(id),4936,openId);String trigger="payment_test_fail_"+id;Map<String,Object> before=state(id);
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON qc_order FOR EACH ROW BEGIN IF NEW.id="+id+" AND NEW.payment_status='PAID' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic payment commit failure'; END IF; END");
        try{assertEquals(500,notification(body,false).getStatusCodeValue());assertEquals(before,state(id));assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM qc_payment WHERE order_id=?",String.class,id));assertNull(jdbc.queryForObject("SELECT transaction_id FROM qc_payment WHERE order_id=?",String.class,id));assertEquals(98,stock());}
        finally{jdbc.execute("DROP TRIGGER "+trigger);}
        assertEquals(204,notification(body,false).getStatusCodeValue());assertEquals("PAID",state(id).get("payment_status"));assertEquals(98,stock());
    }
    String uploadProof(String token) throws Exception
    {
        byte[] pixels=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("..","..","三端配送小程序","static","logo.png"));
        org.springframework.core.io.ByteArrayResource file=new org.springframework.core.io.ByteArrayResource(pixels){@Override public String getFilename(){return "synthetic-proof.png";}};
        org.springframework.util.MultiValueMap<String,Object> parts=new org.springframework.util.LinkedMultiValueMap<String,Object>();parts.add("file",file);
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.MULTIPART_FORM_DATA);headers.set("X-Qicai-Token",token);headers.set("X-Qicai-Privacy-Accepted","1");headers.set("X-Qicai-Privacy-Version",com.ruoyi.qicai.QicaiPrivacyService.VERSION);
        ResponseEntity<JsonNode> result=http.exchange("/qicai/mobile/upload",HttpMethod.POST,new HttpEntity<Object>(parts,headers),JsonNode.class);
        assertEquals(201,result.getStatusCodeValue());String name=result.getBody().path("data").path("fileName").asText();assertFalse(name.isEmpty());
        HttpHeaders auth=new HttpHeaders();auth.set("X-Qicai-Token",token);ResponseEntity<byte[]> image=http.exchange(name,HttpMethod.GET,new HttpEntity<Object>(auth),byte[].class);
        assertEquals(200,image.getStatusCodeValue());assertArrayEquals(pixels,image.getBody());return name;
    }
    @Test void paidOrderRunsThroughRealAssignmentPickupDeliveryReceiptAndRelogin() throws Exception
    {
        long id=order();bind();prepay(id);Map<String,Object> paid=crypto.transaction(trade(id),4936,openId);assertEquals(204,notification(paid,false).getStatusCodeValue());
        ResponseEntity<JsonNode> assigned=call(HttpMethod.POST,"/qicai/admin/deliveries/assign",map("orderId",id,"courierId",courier,"warehouseName","synthetic","warehouseAddress","synthetic","distanceKm",1),adminToken,true);
        assertEquals(200,assigned.getStatusCodeValue());long task=assigned.getBody().path("data").path("id").asLong();assertTrue(task>0);
        String driverPhone=jdbc.queryForObject("SELECT phone FROM qc_courier WHERE id=?",String.class,courier);
        String driver=call(HttpMethod.POST,"/qicai/mobile/courier/login",map("phone",driverPhone,"password","QcPayment2026!"),null,false).getBody().path("data").path("token").asText();assertFalse(driver.isEmpty());
        assertEquals(200,call(HttpMethod.PUT,"/qicai/mobile/courier/tasks/"+task+"/status",map("status","PICKED_UP","pickupProofUrl",uploadProof(driver)),driver,false).getStatusCodeValue());
        assertEquals(200,call(HttpMethod.PUT,"/qicai/mobile/courier/tasks/"+task+"/status",map("status","IN_DELIVERY"),driver,false).getStatusCodeValue());
        assertEquals(200,call(HttpMethod.POST,"/qicai/mobile/orders/"+id+"/receipt",map("signedName","synthetic-signed","proofUrl",uploadProof(customerToken)),customerToken,false).getStatusCodeValue());
        assertEquals("COMPLETED",state(id).get("status"));assertEquals("PAID",state(id).get("payment_status"));assertEquals(98,stock());
        assertEquals("DELIVERED",jdbc.queryForObject("SELECT status FROM qc_delivery_task WHERE id=?",String.class,task));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_delivery_event WHERE delivery_id=? AND status='DELIVERED'",Integer.class,task));
        Map<String,Object> before=state(id);assertEquals(204,notification(paid,false).getStatusCodeValue());assertEquals(before,state(id));
        assertEquals(200,call(HttpMethod.DELETE,"/qicai/mobile/auth/session",null,customerToken,false).getStatusCodeValue());
        assertEquals(401,call(HttpMethod.GET,"/qicai/mobile/orders/"+id+"/payment",null,customerToken,false).getStatusCodeValue());
        String phone=jdbc.queryForObject("SELECT phone FROM qc_customer WHERE id=?",String.class,customer);
        String fresh=call(HttpMethod.POST,"/qicai/mobile/auth/login",map("audience","consumer","phone",phone,"password","QcPayment2026!"),null,false).getBody().path("data").path("token").asText();assertFalse(fresh.isEmpty());
        JsonNode detail=call(HttpMethod.GET,"/qicai/mobile/orders/"+id,null,fresh,false).getBody().path("data");assertEquals("COMPLETED",detail.path("status").asText());assertEquals("PAID",detail.path("paymentStatus").asText());assertEquals("synthetic-signed",detail.path("delivery").path("signedName").asText());
        assertEquals("DELIVERED",call(HttpMethod.GET,"/qicai/mobile/courier/tasks/"+task,null,driver,false).getBody().path("data").path("status").asText());
        java.nio.file.Files.write(java.nio.file.Paths.get(System.getenv("QICAI_EVIDENCE_DIR"),"payment-fulfilment-fixture.json"),json.writeValueAsBytes(map("kind","real HTTP/DB paid-to-receipt; synthetic provider and customers","orderId",id,"orderNo",trade(id),"phone",phone,"taskId",task)));
    }

    void due(long id) { jdbc.update("UPDATE qc_order SET created_at=DATE_SUB(NOW(),INTERVAL 31 MINUTE) WHERE id=?",id); }
    long paidOrder() throws Exception { long id=order();bind();assertEquals(200,prepay(id).getStatusCodeValue());assertEquals(204,notification(crypto.transaction(trade(id),4936,openId),false).getStatusCodeValue());return id; }
    ResponseEntity<JsonNode> refundRequest(long id) { return call(HttpMethod.POST,"/qicai/admin/orders/"+id+"/refund",map("reason","synthetic full refund"),adminToken,true); }
    ResponseEntity<JsonNode> review(long id,boolean approved,String number) { return call(HttpMethod.POST,"/qicai/admin/orders/"+id+"/refund/review",map("approved",approved,"outRefundNo",number,"note","synthetic reviewed"),adminToken,true); }
    String refundNo(long id) { return jdbc.queryForObject("SELECT out_refund_no FROM qc_refund WHERE order_id=?",String.class,id); }
    String refundState(long id) { return jdbc.queryForObject("SELECT status FROM qc_refund WHERE order_id=?",String.class,id); }
    com.wechat.pay.java.service.refund.model.Refund refundResult(long id,String status) {
        return new Gson().fromJson(new Gson().toJson(map("refund_id","wxrefund"+id,"out_refund_no",refundNo(id),"out_trade_no",trade(id),"transaction_id",jdbc.queryForObject("SELECT transaction_id FROM qc_payment WHERE order_id=?",String.class,id),"status",status,"success_time","2026-10-15T08:00:00+08:00","amount",map("total",jdbc.queryForObject("SELECT amount_fen FROM qc_payment WHERE order_id=?",Integer.class,id),"refund",jdbc.queryForObject("SELECT amount_fen FROM qc_payment WHERE order_id=?",Integer.class,id),"currency","CNY"))),com.wechat.pay.java.service.refund.model.Refund.class);
    }
    @Test void deadlineIsFixedAndExactThirtyMinuteBoundaryClosesOnlyOnce() throws Exception {
        long id=order();bind();assertEquals("SKIPPED",payments.expireUnpaid(id));prepay(id);prepay(id);
        org.mockito.ArgumentCaptor<String> deadline=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(provider,times(2)).prepay(eq(trade(id)),eq(4936),eq(openId),deadline.capture());assertEquals(deadline.getAllValues().get(0),deadline.getAllValues().get(1));
        assertEquals(java.time.Instant.ofEpochSecond(jdbc.queryForObject("SELECT UNIX_TIMESTAMP(created_at)+1800 FROM qc_order WHERE id=?",Long.class,id)),java.time.OffsetDateTime.parse(deadline.getValue()).toInstant());
        jdbc.update("UPDATE qc_order SET created_at=DATE_SUB(NOW(),INTERVAL 30 MINUTE) WHERE id=?",id);
        assertEquals(409,prepay(id).getStatusCodeValue());assertEquals("CLOSED",payments.expireUnpaid(id));verify(provider).close(trade(id));assertEquals(100,stock());
        assertEquals("SKIPPED",payments.expireUnpaid(id));assertEquals(100,stock());assertEquals("CANCELLED",state(id).get("status"));
        assertEquals("CLOSED",jdbc.queryForObject("SELECT status FROM qc_payment_maintenance WHERE order_id=?",String.class,id));
    }
    @Test void expiryUnknownClosePreservesResourcesThenSignedPaidQueryWins() throws Exception {
        long id=order();bind();prepay(id);due(id);doThrow(new QicaiApiException(HttpStatus.BAD_GATEWAY,"synthetic timeout")).when(provider).close(trade(id));
        assertEquals("RETRY",payments.expireUnpaid(id));assertEquals("UNPAID",state(id).get("payment_status"));assertEquals(98,stock());
        assertNotNull(jdbc.queryForObject("SELECT next_attempt_at FROM qc_payment_maintenance WHERE order_id=?",java.sql.Timestamp.class,id));
        remote.put(trade(id),new Gson().fromJson(new Gson().toJson(crypto.transaction(trade(id),4936,openId)),Transaction.class));
        assertEquals("PAID",payments.expireUnpaid(id));assertEquals("PAID",state(id).get("payment_status"));assertEquals(98,stock());verify(provider,times(1)).close(trade(id));
        assertEquals("SKIPPED",payments.expireUnpaid(id));
    }
    @Test void expiryWithoutOutboundNeverCallsProviderAndZeroOrdersStillDispatchOrCancel() throws Exception {
        long id=order();due(id);assertEquals("CLOSED",payments.expireUnpaid(id));verify(provider,never()).close(anyString());verify(provider,never()).query(anyString());assertEquals(100,stock());
        jdbc.update("UPDATE qc_product SET consumer_price=0 WHERE id=?",product);
        long zero=order();assertEquals("NO_PAYMENT_REQUIRED",state(zero).get("payment_status"));
        assertEquals(200,call(HttpMethod.POST,"/qicai/admin/deliveries/assign",map("orderId",zero,"courierId",courier,"warehouseName","synthetic","warehouseAddress","synthetic","distanceKm",1),adminToken,true).getStatusCodeValue());
        long another=order();assertEquals(200,cancel(another).getStatusCodeValue());assertEquals(98,stock());
    }
    @Test void expiryQueueGivesUntriedOrdersPriorityOverFiftyRepeatedFailures() throws Exception {
        jdbc.update("UPDATE qc_product SET stock=1000 WHERE id=?",product);
        List<Long> ids=new ArrayList<Long>();bind();
        for(int i=0;i<51;i++){long id=order();ids.add(id);if(i<50){assertEquals(200,prepay(id).getStatusCodeValue());jdbc.update("INSERT INTO qc_payment_maintenance(order_id,status,attempts,checked_at,next_attempt_at) VALUES(?,'RETRY',1,NOW(),DATE_SUB(NOW(),INTERVAL 1 SECOND))",id);}due(id);}
        // Older permanently failing entries must not monopolize every LIMIT 50 batch.
        doThrow(new QicaiApiException(HttpStatus.BAD_GATEWAY,"synthetic close failure")).when(provider).close(anyString());
        payments.expireDueOrders();assertEquals("CANCELLED",state(ids.get(50)).get("status"));
        for(int i=0;i<50;i++)assertEquals("UNPAID",state(ids.get(i)).get("payment_status"));
        assertEquals(900,stock());
        // Leave isolated queue fixtures inert so subsequent tests cannot inherit them.
        jdbc.update("UPDATE qc_payment_maintenance SET next_attempt_at=DATE_ADD(NOW(),INTERVAL 1 DAY) WHERE order_id IN (SELECT id FROM qc_order WHERE customer_id=?)",customer);
    }
    @Test void refundRequestReviewRejectAndStaleApprovalHaveAuditedAuthorization() throws Exception {
        long unpaid=order();assertEquals(409,refundRequest(unpaid).getStatusCodeValue());long id=paidOrder();
        assertEquals(401,call(HttpMethod.POST,"/qicai/admin/orders/"+id+"/refund",map("reason","synthetic"),customerToken,false).getStatusCodeValue());
        assertEquals(401,call(HttpMethod.GET,"/qicai/admin/orders/"+id+"/refund",null,null,false).getStatusCodeValue());
        assertEquals(400,call(HttpMethod.POST,"/qicai/admin/orders/"+id+"/refund",map("reason","synthetic","amountFen",1),adminToken,true).getStatusCodeValue());
        assertEquals(200,refundRequest(id).getStatusCodeValue());String number=refundNo(id);assertEquals(200,refundRequest(id).getStatusCodeValue());assertEquals(number,refundNo(id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_refund_event WHERE order_id=?",Integer.class,id));assertEquals("REFUND_REVIEW",state(id).get("payment_status"));
        assertEquals(409,cancel(id).getStatusCodeValue());assertEquals(409,call(HttpMethod.POST,"/qicai/admin/deliveries/assign",map("orderId",id,"courierId",courier,"warehouseName","synthetic","warehouseAddress","synthetic","distanceKm",1),adminToken,true).getStatusCodeValue());
        assertEquals(200,review(id,false,number).getStatusCodeValue());assertEquals("PAID",state(id).get("payment_status"));assertEquals("REJECTED",refundState(id));
        assertEquals(200,refundRequest(id).getStatusCodeValue());assertNotEquals(number,refundNo(id));assertEquals(409,review(id,true,number).getStatusCodeValue());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM qc_refund_event WHERE order_id=?",Integer.class,id));assertEquals(1,jdbc.queryForObject("SELECT requested_by FROM qc_refund WHERE order_id=?",Integer.class,id));verify(provider,never()).refund(anyString(),anyString(),anyInt(),anyString());
    }
    @Test void refundUnknownOutcomeQueriesSameNumberAndReleasesExactlyOnce() throws Exception {
        long id=paidOrder();refundRequest(id);String number=refundNo(id);
        when(provider.refund(anyString(),anyString(),anyInt(),anyString())).thenThrow(new QicaiApiException(HttpStatus.BAD_GATEWAY,"synthetic timeout"));
        assertEquals(200,review(id,true,number).getStatusCodeValue());assertEquals("PROCESSING",refundState(id));assertEquals("REFUNDING",state(id).get("payment_status"));assertEquals(98,stock());
        assertNotNull(refunds.detail(id).get("lastError"));when(provider.queryRefund(number)).thenReturn(refundResult(id,"SUCCESS"));
        assertEquals("SUCCESS",refunds.refresh(id).get("status"));assertEquals(100,stock());assertEquals("REFUNDED",state(id).get("payment_status"));
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM qc_payment WHERE order_id=?",String.class,id));Map<String,Object> before=state(id);
        assertEquals("SUCCESS",refunds.refresh(id).get("status"));assertEquals(before,state(id));assertEquals(100,stock());verify(provider,times(1)).refund(eq(trade(id)),eq(number),eq(4936),anyString());
        for(String type:Arrays.asList("PAID","REFUND_REQUESTED","REFUND_APPROVED","REFUND_PROCESSING","REFUND_SUCCESS"))assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_order_notice WHERE order_id=? AND event_type=? AND recipient_type='customer'",Integer.class,id,type),type);
        assertEquals(204,notification(crypto.transaction(trade(id),4936,openId),false).getStatusCodeValue());assertEquals(before,state(id));
        assertEquals("REFUNDED",call(HttpMethod.GET,"/qicai/mobile/orders/"+id,null,customerToken,false).getBody().path("data").path("paymentStatus").asText());
    }
    @Test void refundSemanticMismatchAndDatabaseFailureNeverReleaseUntilRetryCommits() throws Exception {
        long id=paidOrder();refundRequest(id);String number=refundNo(id);
        com.wechat.pay.java.service.refund.model.Refund wrong=refundResult(id,"SUCCESS");wrong.getAmount().setRefund(1L);
        when(provider.queryRefund(number)).thenReturn(wrong);review(id,true,number);assertEquals("PROCESSING",refundState(id));assertEquals(98,stock());
        for(String mismatch:Arrays.asList("currency","transaction","order","refundId","time")){
            com.wechat.pay.java.service.refund.model.Refund r=refundResult(id,"SUCCESS");
            if(mismatch.equals("currency"))r.getAmount().setCurrency("USD");if(mismatch.equals("transaction"))r.setTransactionId("wrong");if(mismatch.equals("order"))r.setOutTradeNo("wrong");if(mismatch.equals("refundId"))r.setOutRefundNo("wrong");if(mismatch.equals("time"))r.setSuccessTime("bad");
            when(provider.queryRefund(number)).thenReturn(r);assertEquals("PROCESSING",refunds.refresh(id).get("status"));assertEquals(98,stock());
        }
        when(provider.queryRefund(number)).thenReturn(refundResult(id,"SUCCESS"));String trigger="refund_test_fail_"+id;
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON qc_order FOR EACH ROW BEGIN IF NEW.id="+id+" AND NEW.payment_status='REFUNDED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic refund commit failure'; END IF; END");
        try{assertEquals("PROCESSING",refunds.refresh(id).get("status"));assertEquals(98,stock());assertEquals("REFUNDING",state(id).get("payment_status"));}finally{jdbc.execute("DROP TRIGGER "+trigger);}
        assertEquals("SUCCESS",refunds.refresh(id).get("status"));assertEquals(100,stock());
    }
    @Test void abnormalRefundCanConvergeAfterProviderRecoveryAndClosedCanBeReapplied() throws Exception {
        long id=paidOrder();refundRequest(id);String number=refundNo(id);when(provider.queryRefund(number)).thenReturn(refundResult(id,"ABNORMAL"));review(id,true,number);
        assertEquals("ABNORMAL",refundState(id));assertEquals("REFUNDING",state(id).get("payment_status"));assertEquals(98,stock());
        when(provider.queryRefund(number)).thenReturn(null);assertEquals("ABNORMAL",refunds.refresh(id).get("status"));verify(provider,never()).refund(anyString(),anyString(),anyInt(),anyString());
        when(provider.queryRefund(number)).thenReturn(refundResult(id,"SUCCESS"));assertEquals("SUCCESS",refunds.refresh(id).get("status"));assertEquals(100,stock());
        long closed=paidOrder();refundRequest(closed);String old=refundNo(closed);when(provider.queryRefund(old)).thenReturn(refundResult(closed,"CLOSED"));review(closed,true,old);
        assertEquals("CLOSED",refundState(closed));assertEquals("PAID",state(closed).get("payment_status"));assertEquals(98,stock());refundRequest(closed);assertNotEquals(old,refundNo(closed));
    }
    @Test void differentOrderRefundRequestsAndDuplicateApprovalsRemainConsistent() throws Exception {
        long a=paidOrder(),b=paidOrder();ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{Future<Integer>x=pool.submit(()->{start.await();return refundRequest(a).getStatusCodeValue();});Future<Integer>y=pool.submit(()->{start.await();return refundRequest(b).getStatusCodeValue();});start.countDown();assertEquals(200,x.get(15,TimeUnit.SECONDS));assertEquals(200,y.get(15,TimeUnit.SECONDS));}
        finally{pool.shutdownNow();}
        String number=refundNo(a);when(provider.queryRefund(number)).thenReturn(refundResult(a,"SUCCESS"));pool=Executors.newFixedThreadPool(2);final CountDownLatch gate=new CountDownLatch(1);
        try{Future<Integer>x=pool.submit(()->{gate.await();return review(a,true,number).getStatusCodeValue();});Future<Integer>y=pool.submit(()->{gate.await();return review(a,true,number).getStatusCodeValue();});gate.countDown();assertEquals(200,x.get(15,TimeUnit.SECONDS));assertEquals(200,y.get(15,TimeUnit.SECONDS));}
        finally{pool.shutdownNow();}
        assertEquals(98,stock());assertEquals("SUCCESS",refundState(a));assertEquals("REQUESTED",refundState(b));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_refund_event WHERE order_id=? AND status='SUCCESS'",Integer.class,a));
    }


    long coupon() {
        String name="synthetic-workflow-"+UUID.randomUUID();jdbc.update("INSERT INTO qc_coupon(name,discount_amount,threshold_amount,audience,valid_from,valid_to,status) VALUES(?,5.55,0,'consumer',DATE_SUB(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 1 DAY),'ACTIVE')",name);
        long coupon=jdbc.queryForObject("SELECT id FROM qc_coupon WHERE name=?",Long.class,name);jdbc.update("INSERT INTO qc_customer_coupon(customer_id,coupon_id,status) VALUES(?,?,'AVAILABLE')",customer,coupon);
        return jdbc.queryForObject("SELECT id FROM qc_customer_coupon WHERE customer_id=? AND coupon_id=?",Long.class,customer,coupon);
    }
    void couponState(long claim,String status,Long order) {Map<String,Object> row=jdbc.queryForMap("SELECT status,used_order_id,used_at FROM qc_customer_coupon WHERE id=?",claim);assertEquals(status,row.get("status"));assertEquals(order,row.get("used_order_id"));if(order==null)assertNull(row.get("used_at"));else assertNotNull(row.get("used_at"));}
    @Test void expiryAndRefundReleaseCouponOnlyAfterConfirmedOutcome() throws Exception {
        long claim=coupon(),id=order(claim);bind();assertEquals(200,prepay(id).getStatusCodeValue());due(id);couponState(claim,"USED",id);
        doThrow(new QicaiApiException(HttpStatus.BAD_GATEWAY,"synthetic close timeout")).when(provider).close(trade(id));assertEquals("RETRY",payments.expireUnpaid(id));couponState(claim,"USED",id);assertEquals(98,stock());
        Map<String,Object> closed=crypto.transaction(trade(id),4381,openId);closed.put("trade_state","CLOSED");closed.put("mchid","wrong");remote.put(trade(id),new Gson().fromJson(new Gson().toJson(closed),Transaction.class));assertEquals("RETRY",payments.expireUnpaid(id));couponState(claim,"USED",id);
        closed.put("mchid",config.getMerchantId());remote.put(trade(id),new Gson().fromJson(new Gson().toJson(closed),Transaction.class));assertEquals("CLOSED",payments.expireUnpaid(id));verify(provider,times(1)).close(trade(id));couponState(claim,"AVAILABLE",null);assertEquals(100,stock());assertEquals("SKIPPED",payments.expireUnpaid(id));
        long paid=order(claim);assertEquals(200,prepay(paid).getStatusCodeValue());assertEquals(204,notification(crypto.transaction(trade(paid),4381,openId),false).getStatusCodeValue());refundRequest(paid);String no=refundNo(paid);when(provider.queryRefund(no)).thenReturn(refundResult(paid,"PROCESSING"));review(paid,true,no);couponState(claim,"USED",paid);assertEquals(98,stock());
        when(provider.queryRefund(no)).thenReturn(refundResult(paid,"SUCCESS"));refunds.refresh(paid);couponState(claim,"AVAILABLE",null);assertEquals(100,stock());refunds.refresh(paid);couponState(claim,"AVAILABLE",null);assertEquals(100,stock());
    }
    @Test void differentCustomersCanInitializePaymentConcurrently() throws Exception {
        long a=order();bind();String phone=String.format("13976%06d",sequence.incrementAndGet());
        jdbc.update("INSERT INTO qc_customer(audience,phone,password_hash,contact_name,address,status) VALUES('consumer',?,?,'synthetic-other','synthetic','ACTIVE')",phone,passwords.encode("QcPayment2026!"));
        String token=call(HttpMethod.POST,"/qicai/mobile/auth/login",map("audience","consumer","phone",phone,"password","QcPayment2026!"),null,false).getBody().path("data").path("token").asText();
        assertEquals(200,call(HttpMethod.POST,"/qicai/mobile/payments/wechat/identity",map("code","fresh-"+UUID.randomUUID()),token,false).getStatusCodeValue());
        long b=call(HttpMethod.POST,"/qicai/mobile/orders",map("productId",product,"quantity",2,"idempotencyKey",UUID.randomUUID().toString(),"deliveryAddress","synthetic"),token,false).getBody().path("data").path("id").asLong();assertTrue(b>0);
        JdbcTemplate original=(JdbcTemplate)org.springframework.test.util.ReflectionTestUtils.getField(payments,"jdbc");CyclicBarrier barrier=new CyclicBarrier(2);
        JdbcTemplate observed=new JdbcTemplate(original.getDataSource()){@Override public List<Map<String,Object>> queryForList(String sql,Object... args){List<Map<String,Object>> result=super.queryForList(sql,args);if(sql.startsWith("SELECT * FROM qc_payment WHERE order_id=")&&result.isEmpty()){try{barrier.await(8,TimeUnit.SECONDS);}catch(Exception ex){throw new AssertionError(ex);}}return result;}};
        org.springframework.test.util.ReflectionTestUtils.setField(payments,"jdbc",observed);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Future<Integer>x=pool.submit(()->prepay(a).getStatusCodeValue());Future<Integer>y=pool.submit(()->call(HttpMethod.POST,"/qicai/mobile/orders/"+b+"/wechat-prepay",map(),token,false).getStatusCodeValue());assertEquals(200,x.get(15,TimeUnit.SECONDS));assertEquals(200,y.get(15,TimeUnit.SECONDS));}
        finally{pool.shutdownNow();org.springframework.test.util.ReflectionTestUtils.setField(payments,"jdbc",original);}
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM qc_payment WHERE order_id IN (?,?) AND status='PENDING'",Integer.class,a,b));assertEquals(96,stock());
    }
    @Test void actualScheduledMaintenanceClosesOverdueAndReconcilesPersistedRefund() throws Exception {
        long expired=order();due(expired);long paid=paidOrder();refundRequest(paid);String no=refundNo(paid);
        when(provider.refund(anyString(),anyString(),anyInt(),anyString())).thenThrow(new QicaiApiException(HttpStatus.BAD_GATEWAY,"synthetic timeout"));review(paid,true,no);
        when(provider.queryRefund(no)).thenReturn(refundResult(paid,"SUCCESS"));jdbc.update("UPDATE qc_refund SET next_attempt_at=NOW() WHERE order_id=?",paid);
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();scheduler.initialize();
        org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor processor=new org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor();processor.setScheduler(scheduler);
        try{processor.postProcessAfterInitialization(new PaymentMaintenanceJob(payments,refunds),"isolated-real-maintenance");processor.afterSingletonsInstantiated();assertEquals(1,processor.getScheduledTasks().size());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(85);while(System.nanoTime()<deadline&&(!"CANCELLED".equals(state(expired).get("status"))||!"SUCCESS".equals(refundState(paid))))Thread.sleep(200);
            assertEquals("CANCELLED",state(expired).get("status"));assertEquals("SUCCESS",refundState(paid));assertEquals(100,stock());
        }finally{processor.destroy();scheduler.shutdown();}
    }
    @Test void liveBrowserAdminRefundAndCourierExceptionPersistAcrossRefresh() throws Exception {
        long id=paidOrder(),delivery=paidOrder();
        long task=call(HttpMethod.POST,"/qicai/admin/deliveries/assign",map("orderId",delivery,"courierId",courier,"warehouseName","synthetic","warehouseAddress","synthetic","distanceKm",1),adminToken,true).getBody().path("data").path("id").asLong();assertTrue(task>0);
        String courierPhone=jdbc.queryForObject("SELECT phone FROM qc_courier WHERE id=?",String.class,courier);
        String driver=call(HttpMethod.POST,"/qicai/mobile/courier/login",map("phone",courierPhone,"password","QcPayment2026!"),null,false).getBody().path("data").path("token").asText();
        assertEquals(200,call(HttpMethod.PUT,"/qicai/mobile/courier/tasks/"+task+"/status",map("status","PICKED_UP","pickupProofUrl",uploadProof(driver)),driver,false).getStatusCodeValue());
        assertEquals(200,call(HttpMethod.PUT,"/qicai/mobile/courier/tasks/"+task+"/status",map("status","IN_DELIVERY"),driver,false).getStatusCodeValue());
        AtomicInteger queries=new AtomicInteger();when(provider.queryRefund(anyString())).thenAnswer(a->queries.incrementAndGet()==1?null:refundResult(id,"SUCCESS"));when(provider.refund(anyString(),anyString(),anyInt(),anyString())).thenAnswer(a->refundResult(id,"PROCESSING"));
        String node=System.getenv("QICAI_NODE_BIN");assertNotNull(node,"Use a validated installed Node runtime for browser integration");
        java.nio.file.Path root=java.nio.file.Paths.get("..","..").toAbsolutePath().normalize();
        Process process=new ProcessBuilder(node,"scripts/workflow-browser-regression.cjs").directory(root.toFile()).redirectErrorStream(true).redirectOutput(java.nio.file.Paths.get(System.getenv("QICAI_EVIDENCE_DIR"),"workflow-browser.log").toFile()).start();
        try{process.getOutputStream().write(json.writeValueAsBytes(map("apiPort",port,"orderId",id,"orderNo",trade(id),"phone",jdbc.queryForObject("SELECT phone FROM qc_customer WHERE id=?",String.class,customer),"taskId",task,"deliveryOrderId",delivery,"courierPhone",courierPhone)));process.getOutputStream().close();assertTrue(process.waitFor(150,TimeUnit.SECONDS),"browser timed out; see workflow-browser.log");assertEquals(0,process.exitValue(),"see workflow-browser.log");}finally{if(process.isAlive())process.destroyForcibly();}
        assertEquals("REFUNDED",state(id).get("payment_status"));assertEquals("SUCCESS",refundState(id));assertEquals(98,stock());assertEquals("IN_DELIVERY",state(delivery).get("status"));assertEquals("IN_DELIVERY",jdbc.queryForObject("SELECT status FROM qc_delivery_task WHERE id=?",String.class,task));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_delivery_event WHERE delivery_id=? AND note LIKE '%恢复原阶段%'",Integer.class,task));
    }


    @Test void refundAdminRoleAndSessionBoundariesRejectBeforeAnyMutation() throws Exception {
        long id=paidOrder();String user="workflow_user_"+sequence.incrementAndGet();jdbc.update("INSERT INTO sys_user(dept_id,user_name,nick_name,password,status,del_flag) VALUES(101,?,'synthetic',?,'0','0')",user,passwords.encode("QcPayment2026!"));
        String ordinary=call(HttpMethod.POST,"/login",map("username",user,"password","QcPayment2026!"),null,false).getBody().path("token").asText();assertFalse(ordinary.isEmpty());
        String driver=call(HttpMethod.POST,"/qicai/mobile/courier/login",map("phone",jdbc.queryForObject("SELECT phone FROM qc_courier WHERE id=?",String.class,courier),"password","QcPayment2026!"),null,false).getBody().path("data").path("token").asText();
        for(String suffix:Arrays.asList("/refund","/refund/review","/refund/refresh")){
            ResponseEntity<JsonNode> denied=call(HttpMethod.POST,"/qicai/admin/orders/"+id+suffix,map("reason","synthetic","approved",true,"outRefundNo","forged","note","synthetic"),ordinary,true);
            assertEquals(403,denied.getBody().path("code").asInt());assertTrue(Arrays.asList(200,403).contains(denied.getStatusCodeValue()));
            assertEquals(401,call(HttpMethod.POST,"/qicai/admin/orders/"+id+suffix,map(),driver,false).getStatusCodeValue());
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qc_refund WHERE order_id=?",Integer.class,id));assertEquals("PAID",state(id).get("payment_status"));assertEquals(98,stock());
        assertEquals(200,call(HttpMethod.POST,"/logout",map(),ordinary,true).getStatusCodeValue());assertEquals(401,call(HttpMethod.GET,"/qicai/admin/orders/"+id+"/refund",null,ordinary,true).getStatusCodeValue());
        verify(provider,never()).refund(anyString(),anyString(),anyInt(),anyString());
    }
    @Test void differentCouriersCanAssignDifferentOrdersAtSameMissingRowBoundary() throws Exception {
        long a=paidOrder(),b=paidOrder();String phone=String.format("13876%06d",sequence.incrementAndGet());jdbc.update("INSERT INTO qc_courier(phone,password_hash,name,vehicle_no,status,available) VALUES(?,?,'synthetic-other','TEST','ACTIVE',1)",phone,passwords.encode("QcPayment2026!"));long other=jdbc.queryForObject("SELECT id FROM qc_courier WHERE phone=?",Long.class,phone);
        JdbcTemplate original=(JdbcTemplate)org.springframework.test.util.ReflectionTestUtils.getField(qicai,"jdbc");CyclicBarrier barrier=new CyclicBarrier(2);
        JdbcTemplate observed=new JdbcTemplate(original.getDataSource()){@Override public List<Map<String,Object>> queryForList(String sql,Object... args){List<Map<String,Object>> rows=super.queryForList(sql,args);if(sql.startsWith("SELECT id,status FROM qc_delivery_task WHERE order_id=")&&rows.isEmpty()){try{barrier.await(8,TimeUnit.SECONDS);}catch(Exception ex){throw new AssertionError(ex);}}return rows;}};
        org.springframework.test.util.ReflectionTestUtils.setField(qicai,"jdbc",observed);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Future<Integer>x=pool.submit(()->call(HttpMethod.POST,"/qicai/admin/deliveries/assign",map("orderId",a,"courierId",courier,"warehouseName","synthetic","warehouseAddress","synthetic","distanceKm",1),adminToken,true).getStatusCodeValue());Future<Integer>y=pool.submit(()->call(HttpMethod.POST,"/qicai/admin/deliveries/assign",map("orderId",b,"courierId",other,"warehouseName","synthetic","warehouseAddress","synthetic","distanceKm",1),adminToken,true).getStatusCodeValue());assertEquals(200,x.get(15,TimeUnit.SECONDS));assertEquals(200,y.get(15,TimeUnit.SECONDS));}
        finally{pool.shutdownNow();org.springframework.test.util.ReflectionTestUtils.setField(qicai,"jdbc",original);}
        assertEquals("ASSIGNED",state(a).get("status"));assertEquals("ASSIGNED",state(b).get("status"));assertEquals(courier,jdbc.queryForObject("SELECT courier_id FROM qc_delivery_task WHERE order_id=?",Long.class,a));assertEquals(other,jdbc.queryForObject("SELECT courier_id FROM qc_delivery_task WHERE order_id=?",Long.class,b));assertEquals(96,stock());
    }
    @Test void committedIntentRechecksDisableExpiryCancellationAndCallbackBeforeSending() throws Exception {
        bind();
        for(String change:Arrays.asList("disable","expire","cancel","paid")) {
            long id=order();
            org.springframework.transaction.support.TransactionTemplate original=(org.springframework.transaction.support.TransactionTemplate)org.springframework.test.util.ReflectionTestUtils.getField(payments,"tx");
            AtomicInteger phases=new AtomicInteger();
            org.springframework.transaction.support.TransactionTemplate observed=new org.springframework.transaction.support.TransactionTemplate(transactionManager){
                @Override public <T>T execute(org.springframework.transaction.support.TransactionCallback<T> action){
                    T result=original.execute(action);
                    if(phases.incrementAndGet()==2){
                        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM qc_payment WHERE order_id=?",String.class,id));
                        assertEquals(1,jdbc.queryForObject("SELECT uncertain_attempts FROM qc_payment WHERE order_id=?",Integer.class,id));
                        if(change.equals("disable"))jdbc.update("UPDATE qc_customer SET status='DISABLED' WHERE id=?",customer);
                        if(change.equals("expire"))due(id);
                        if(change.equals("cancel"))assertEquals(200,cancel(id).getStatusCodeValue());
                        if(change.equals("paid"))try{assertEquals(204,notification(crypto.transaction(trade(id),4936,openId),false).getStatusCodeValue());}catch(Exception ex){throw new AssertionError(ex);}
                    }
                    return result;
                }
            };
            org.springframework.test.util.ReflectionTestUtils.setField(payments,"tx",observed);
            try{assertEquals(change.equals("disable")?403:409,prepay(id).getStatusCodeValue());}
            finally{org.springframework.test.util.ReflectionTestUtils.setField(payments,"tx",original);jdbc.update("UPDATE qc_customer SET status='ACTIVE' WHERE id=?",customer);}
            verify(provider,never()).prepay(eq(trade(id)),anyInt(),anyString(),anyString());
            if(change.equals("disable")||change.equals("expire")){assertEquals("REJECTED",jdbc.queryForObject("SELECT status FROM qc_payment WHERE order_id=?",String.class,id));assertEquals(0,jdbc.queryForObject("SELECT uncertain_attempts FROM qc_payment WHERE order_id=?",Integer.class,id));}
            if(change.equals("cancel"))assertEquals("CANCELLED",state(id).get("status"));
            if(change.equals("paid"))assertEquals("PAID",state(id).get("payment_status"));
        }
    }

    @Test void unsentIntentReconciliationPreservesAnotherUnknownAttemptAndRejectsOuterTransactions() throws Exception {
        long id=order();bind();
        when(provider.prepay(anyString(),anyInt(),anyString(),anyString())).thenThrow(new QicaiApiException(HttpStatus.BAD_GATEWAY,"synthetic unknown"));
        assertEquals(502,prepay(id).getStatusCodeValue());assertEquals(1,jdbc.queryForObject("SELECT uncertain_attempts FROM qc_payment WHERE order_id=?",Integer.class,id));
        org.springframework.transaction.support.TransactionTemplate original=(org.springframework.transaction.support.TransactionTemplate)org.springframework.test.util.ReflectionTestUtils.getField(payments,"tx");
        AtomicInteger phases=new AtomicInteger();
        org.springframework.transaction.support.TransactionTemplate observed=new org.springframework.transaction.support.TransactionTemplate(transactionManager){
            @Override public <T>T execute(org.springframework.transaction.support.TransactionCallback<T> action){T result=original.execute(action);if(phases.incrementAndGet()==2)jdbc.update("UPDATE qc_customer SET status='DISABLED' WHERE id=?",customer);return result;}};
        org.springframework.test.util.ReflectionTestUtils.setField(payments,"tx",observed);
        try{assertEquals(403,prepay(id).getStatusCodeValue());}
        finally{org.springframework.test.util.ReflectionTestUtils.setField(payments,"tx",original);jdbc.update("UPDATE qc_customer SET status='ACTIVE' WHERE id=?",customer);}
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM qc_payment WHERE order_id=?",String.class,id));assertEquals(1,jdbc.queryForObject("SELECT uncertain_attempts FROM qc_payment WHERE order_id=?",Integer.class,id));verify(provider,times(1)).prepay(eq(trade(id)),anyInt(),anyString(),anyString());
        long untouched=order();
        assertThrows(IllegalStateException.class,()->original.execute(state->payments.prepay(new com.ruoyi.qicai.QicaiAuthService.Principal("customer",customer,"consumer"),untouched)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qc_payment WHERE order_id=?",Integer.class,untouched));
    }

    @Test void confirmedRefundRecoversStockBeyondOldIntBoundaryExactlyOnce() throws Exception {
        long id=paidOrder();jdbc.update("UPDATE qc_product SET stock=2147483647 WHERE id=?",product);refundRequest(id);String number=refundNo(id);
        when(provider.queryRefund(number)).thenReturn(refundResult(id,"SUCCESS"));review(id,true,number);
        assertEquals("REFUNDED",state(id).get("payment_status"));assertEquals(2147483649L,jdbc.queryForObject("SELECT stock FROM qc_product WHERE id=?",Long.class,product));
        refunds.refresh(id);assertEquals(2147483649L,jdbc.queryForObject("SELECT stock FROM qc_product WHERE id=?",Long.class,product));
    }

    @Test void refundBatchContinuesPastOnePersistenceFailureAndPreservesAbnormalState() throws Exception {
        long a=paidOrder(),b=paidOrder();refundRequest(a);refundRequest(b);String an=refundNo(a),bn=refundNo(b);when(provider.queryRefund(an)).thenReturn(refundResult(a,"ABNORMAL"));when(provider.queryRefund(bn)).thenReturn(refundResult(b,"PROCESSING"));review(a,true,an);review(b,true,bn);
        when(provider.queryRefund(an)).thenThrow(new QicaiApiException(HttpStatus.BAD_GATEWAY,"synthetic unknown"));assertEquals("ABNORMAL",refunds.refresh(a).get("status"));assertEquals("ABNORMAL",jdbc.queryForObject("SELECT status FROM qc_refund_event WHERE order_id=? ORDER BY id DESC LIMIT 1",String.class,a));
        jdbc.update("UPDATE qc_refund SET next_attempt_at=DATE_SUB(NOW(),INTERVAL 1 HOUR) WHERE order_id IN (?,?)",a,b);when(provider.queryRefund(bn)).thenReturn(refundResult(b,"SUCCESS"));String trigger="refund_claim_fail_"+a;
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON qc_refund FOR EACH ROW BEGIN IF NEW.order_id="+a+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic claim failure'; END IF; END");
        try{assertTrue(refunds.refreshDueRefunds()>0);assertEquals("ABNORMAL",refundState(a));assertEquals("SUCCESS",refundState(b));assertEquals(98,stock());}finally{jdbc.execute("DROP TRIGGER "+trigger);jdbc.update("UPDATE qc_refund SET next_attempt_at=DATE_ADD(NOW(),INTERVAL 1 DAY) WHERE order_id=?",a);}
    }

}

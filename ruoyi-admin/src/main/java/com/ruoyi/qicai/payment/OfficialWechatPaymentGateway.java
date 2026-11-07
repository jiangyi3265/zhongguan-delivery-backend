package com.ruoyi.qicai.payment;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import com.ruoyi.qicai.QicaiApiException;

@Component
public class OfficialWechatPaymentGateway implements WechatPaymentGateway
{
    private static final Logger log = LoggerFactory.getLogger(OfficialWechatPaymentGateway.class);
    private static final ThreadLocal<DiagnosticAttempt> diagnosticContext = new ThreadLocal<>();
    // Error codes are provider-defined identifiers. Never log arbitrary response
    // values, exception text or response bodies, even if they resemble an identifier.
    private static final java.util.Set<String> diagnosticCodes = new java.util.HashSet<>(java.util.Arrays.asList(
            "SYSTEM_ERROR", "PARAM_ERROR", "APPID_MCHID_NOT_MATCH", "NO_AUTH", "SIGN_ERROR", "INVALID_REQUEST",
            "ORDERPAID", "ORDERCLOSED", "ORDER_CLOSED", "ORDER_NOT_EXIST", "ORDERNOTEXIST",
            "RESOURCE_NOT_EXISTS", "RESOURCE_NOT_EXIST", "FREQUENCY_LIMITED", "RATE_LIMITED", "RULE_LIMIT",
            "NOT_ENOUGH", "USERPAYING", "INVALID_TRANSACTIONID", "OUT_TRADE_NO_USED", "INVALID_APPID",
            "INVALID_MCHID", "MCH_NOT_EXISTS", "APPID_NOT_EXIST", "REFUND_NOT_EXIST", "REFUNDNOTEXIST", "TRADE_ERROR"));
    private final WechatPayProperties properties;
    private final ObjectMapper json;
    private JsapiServiceExtension service;
    private com.wechat.pay.java.service.refund.RefundService refundService;
    private NotificationParser parser;
    private RestTemplate wechatLogin;
    private String appSecret;

    public OfficialWechatPaymentGateway(WechatPayProperties properties, ObjectMapper json)
    {
        this.properties = properties;
        this.json = json;
    }

    @PostConstruct
    public void initialize()
    {
        // Mini-program identity needs only AppID/AppSecret, independently of payment.
        // An absent login configuration is reported by the login endpoint as 503.
        try
        {
            if (properties.getAppId().matches("wx[0-9a-fA-F]{16}"))
            {
                appSecret = readSecret(properties.getAppSecretFile());
                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(3000);
                factory.setReadTimeout(4000);
                wechatLogin = new RestTemplate(factory);
            }
        }
        catch (Exception ex) { appSecret = null; wechatLogin = null; }
        if (!properties.isEnabled()) return;
        try
        {
            URI notify = URI.create(properties.getNotifyUrl());
            if (!properties.getAppId().matches("wx[0-9a-fA-F]{16}") || !properties.getMerchantId().matches("[0-9]{6,32}")
                    || !properties.getPublicKeyId().matches("PUB_KEY_ID_[0-9]+")
                    || !"https".equals(notify.getScheme()) || notify.getHost() == null
                    || notify.getUserInfo() != null || notify.getQuery() != null || notify.getFragment() != null
                    || !notify.getPath().endsWith("/qicai/payment/wechat/notify"))
                throw new IllegalArgumentException();
            String apiKey = readSecret(properties.getApiV3KeyFile());
            if (apiKey.getBytes(StandardCharsets.UTF_8).length != 32) throw new IllegalArgumentException();
            if (wechatLogin == null) throw new IllegalArgumentException();
            RSAPublicKeyConfig config = new RSAPublicKeyConfig.Builder()
                    .merchantId(properties.getMerchantId()).merchantSerialNumber(properties.getMerchantSerialNumber())
                    .privateKeyFromPath(properties.getPrivateKeyPath()).publicKeyFromPath(properties.getPublicKeyPath())
                    .publicKeyId(properties.getPublicKeyId()).apiV3Key(apiKey).build();
            com.wechat.pay.java.core.http.HttpClient client = new DefaultHttpClientBuilder().config(config)
                            .okHttpClient(paymentHttpClient()).build();
            service = new JsapiServiceExtension.Builder().config(config).httpClient(client).build();
            refundService = new com.wechat.pay.java.service.refund.RefundService.Builder().config(config).httpClient(client).build();
            parser = new NotificationParser(config);
        }
        catch (Exception ex)
        {
            // SDK exceptions can contain raw request bodies/credentials: do not attach them.
            throw new IllegalStateException("微信支付已启用，但服务端配置或密钥文件无效");
        }
    }

    static okhttp3.OkHttpClient paymentHttpClient()
    {
        // WeChat can close an idle keep-alive connection without the client
        // noticing. Reusing it produced immediate EOF failures after ~15 seconds.
        // Do not retain idle connections, and do not retry uncertain financial
        // requests: a missing response does not mean the provider did not act.
        return new okhttp3.OkHttpClient.Builder().addInterceptor(responseHeaderNormalizer())
                .connectionPool(new okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
                .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    }

    // SDK 0.2.17 copies response headers into a case-sensitive HashMap. HTTP
    // header names are case-insensitive (and lowercase in HTTP/2); preserve
    // their values/body, then let the SDK perform the original RSA validation.
    static okhttp3.Interceptor responseHeaderNormalizer()
    {
        return chain -> {
            DiagnosticAttempt diagnostic = diagnosticContext.get();
            // A future SDK retry may reuse the logical call scope. Clear before
            // transport so a headerless response or timeout cannot inherit a trace.
            if (diagnostic != null)
            {
                diagnostic.httpStatus = 0;
                diagnostic.requestId = "unavailable";
                diagnostic.invalidHeaders = false;
            }
            okhttp3.Response response = chain.proceed(chain.request());
            if (diagnostic != null)
            {
                diagnostic.httpStatus = response.code();
                java.util.List<String> ids = response.headers("Request-ID");
                if (ids.size() == 1 && ids.get(0).matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
                    diagnostic.requestId = ids.get(0);
            }
            okhttp3.Headers.Builder headers = response.headers().newBuilder();
            for (String name : new String[] {"Wechatpay-Timestamp", "Wechatpay-Nonce", "Wechatpay-Serial", "Wechatpay-Signature", "Request-ID"})
            {
                java.util.List<String> values = response.headers(name);
                if (values.size() > 1)
                {
                    if (diagnostic != null) diagnostic.invalidHeaders = true;
                    response.close();
                    throw new java.io.IOException("Ambiguous payment response headers");
                }
                if (values.size() == 1) headers.set(name, values.get(0));
            }
            return response.newBuilder().headers(headers.build()).build();
        };
    }

    /** Per synchronous SDK call; never retains order IDs, bodies, credentials or exception objects. */
    private static final class DiagnosticAttempt implements AutoCloseable
    {
        final String operation;
        final String correlationId = java.util.UUID.randomUUID().toString();
        final long started = System.nanoTime();
        final DiagnosticAttempt previous = diagnosticContext.get();
        int httpStatus;
        String requestId = "unavailable";
        boolean invalidHeaders;

        DiagnosticAttempt(String operation)
        {
            this.operation = operation;
            diagnosticContext.set(this);
        }

        void failure(Exception failure)
        {
            String kind = "unexpected_failure";
            if (failure instanceof com.wechat.pay.java.core.exception.ServiceException) kind = "service_error";
            else if (failure instanceof com.wechat.pay.java.core.exception.ValidationException) kind = "response_validation";
            else if (failure instanceof com.wechat.pay.java.core.exception.MalformedMessageException
                    || failure instanceof QicaiApiException) kind = "response_invalid";
            else if (failure instanceof com.wechat.pay.java.core.exception.HttpException) kind = "transport_error";
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause())
            {
                if (cause instanceof java.net.SocketTimeoutException) { kind = "transport_timeout"; break; }
                if (cause instanceof java.io.InterruptedIOException) { kind = "transport_timeout_or_interrupted"; break; }
                if (cause instanceof java.io.EOFException) { kind = "transport_eof"; break; }
                if (cause instanceof java.net.UnknownHostException) { kind = "transport_dns"; break; }
                if (cause instanceof javax.net.ssl.SSLException) { kind = "transport_tls"; break; }
                if (cause instanceof java.net.ConnectException) { kind = "transport_connect"; break; }
            }
            record(kind, failure);
        }

        void record(String kind, Exception failure)
        {
            String code = "unavailable";
            if (failure instanceof com.wechat.pay.java.core.exception.ServiceException)
            {
                com.wechat.pay.java.core.exception.ServiceException serviceFailure =
                        (com.wechat.pay.java.core.exception.ServiceException) failure;
                if (httpStatus == 0) httpStatus = serviceFailure.getHttpStatusCode();
                code = diagnosticCodes.contains(serviceFailure.getErrorCode()) ? serviceFailure.getErrorCode() : "OTHER";
            }
            if (httpStatus < 100 || httpStatus > 599) httpStatus = 0;
            if (invalidHeaders) kind = "response_headers_invalid";
            long elapsed = Math.max(0L, (System.nanoTime() - started) / 1000000L);
            String format = "wechat_payment_diagnostic op={} kind={} status={} code={} request_id={} correlation={} elapsed_ms={}";
            // Metadata only. Passing the SDK Throwable would expose its request/response bodies.
            if ("not_found".equals(kind) || "already_closed".equals(kind))
                log.info(format, operation, kind, httpStatus, code, requestId, correlationId, elapsed);
            else log.warn(format, operation, kind, httpStatus, code, requestId, correlationId, elapsed);
        }

        @Override public void close()
        {
            if (previous == null) diagnosticContext.remove();
            else diagnosticContext.set(previous);
        }
    }

    private static String readSecret(String path) throws Exception
    {
        if (path.isEmpty() || Files.size(Paths.get(path)) > 4096) throw new IllegalArgumentException();
        String value = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).trim();
        if (value.isEmpty()) throw new IllegalArgumentException();
        return value;
    }

    private void ready()
    {
        if (service == null) throw new QicaiApiException(HttpStatus.SERVICE_UNAVAILABLE, "微信支付尚未启用");
    }

    @Override
    public String exchangeCode(String code)
    {
        if (wechatLogin == null || appSecret == null)
            throw new QicaiApiException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录尚未配置");
        try
        {
            URI uri = UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/sns/jscode2session")
                    .queryParam("appid", properties.getAppId()).queryParam("secret", appSecret)
                    .queryParam("js_code", code).queryParam("grant_type", "authorization_code").build().encode().toUri();
            JsonNode result = json.readTree(wechatLogin.getForObject(uri, String.class));
            String openId = result.path("openid").asText("");
            if (result.path("errcode").asInt(0) != 0 || openId.isEmpty() || openId.length() > 128)
                throw new QicaiApiException(HttpStatus.BAD_REQUEST, "微信登录凭证无效，请重新授权");
            return openId;
        }
        catch (QicaiApiException ex) { throw ex; }
        catch (Exception ex) { throw upstream(); }
    }

    @Override
    public Map<String, Object> prepay(String tradeNo, int amountFen, String openId, String expiresAt)
    {
        ready();
        PrepayRequest request = new PrepayRequest();
        request.setAppid(properties.getAppId());
        request.setMchid(properties.getMerchantId());
        request.setDescription("中关数字产业采购订单");
        request.setOutTradeNo(tradeNo);
        request.setNotifyUrl(properties.getNotifyUrl());
        request.setTimeExpire(expiresAt);
        Amount amount = new Amount(); amount.setTotal(amountFen); amount.setCurrency("CNY"); request.setAmount(amount);
        Payer payer = new Payer(); payer.setOpenid(openId); request.setPayer(payer);
        DiagnosticAttempt diagnostic = new DiagnosticAttempt("prepay");
        try
        {
            PrepayWithRequestPaymentResponse r = service.prepayWithRequestPayment(request);
            if (r.getPackageVal() == null || !r.getPackageVal().matches("prepay_id=[A-Za-z0-9_-]{1,128}")
                    || "prepay_id=null".equals(r.getPackageVal())) throw upstream();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("timeStamp", r.getTimeStamp()); result.put("nonceStr", r.getNonceStr());
            result.put("package", r.getPackageVal()); result.put("signType", r.getSignType()); result.put("paySign", r.getPaySign());
            return result;
        }
        catch (com.wechat.pay.java.core.exception.ServiceException ex)
        {
            if (java.util.Arrays.asList("APPID_MCHID_NOT_MATCH", "PARAM_ERROR", "NO_AUTH").contains(ex.getErrorCode()))
            {
                diagnostic.record("explicit_rejection", ex);
                throw new WechatPrepayRejectedException();
            }
            diagnostic.failure(ex);
            throw upstream();
        }
        catch (Exception ex) { diagnostic.failure(ex); throw upstream(); }
        finally { diagnostic.close(); }
    }

    @Override
    public Transaction query(String tradeNo)
    {
        ready();
        QueryOrderByOutTradeNoRequest r = new QueryOrderByOutTradeNoRequest();
        r.setMchid(properties.getMerchantId()); r.setOutTradeNo(tradeNo);
        DiagnosticAttempt diagnostic = new DiagnosticAttempt("query");
        try { return service.queryOrderByOutTradeNo(r); }
        catch (com.wechat.pay.java.core.exception.ServiceException ex)
        {
            if ("ORDER_NOT_EXIST".equals(ex.getErrorCode())) { diagnostic.record("not_found", ex); return null; }
            diagnostic.failure(ex);
            throw upstream();
        }
        catch (Exception ex) { diagnostic.failure(ex); throw upstream(); }
        finally { diagnostic.close(); }
    }

    @Override
    public void close(String tradeNo)
    {
        ready();
        CloseOrderRequest r = new CloseOrderRequest(); r.setMchid(properties.getMerchantId()); r.setOutTradeNo(tradeNo);
        DiagnosticAttempt diagnostic = new DiagnosticAttempt("close");
        try { service.closeOrder(r); }
        catch (com.wechat.pay.java.core.exception.ServiceException ex)
        {
            if ("ORDERPAID".equals(ex.getErrorCode()))
            {
                diagnostic.record("order_paid", ex);
                throw new QicaiApiException(HttpStatus.CONFLICT, "微信订单已付款，请刷新支付状态，不能直接取消");
            }
            // NOT_EXIST is not a confirmed closure after a timed-out prepay request.
            if (!"ORDER_CLOSED".equals(ex.getErrorCode())) { diagnostic.failure(ex); throw upstream(); }
            diagnostic.record("already_closed", ex);
        }
        catch (Exception ex) { diagnostic.failure(ex); throw upstream(); }
        finally { diagnostic.close(); }
    }

    @Override
    public com.wechat.pay.java.service.refund.model.Refund refund(String tradeNo, String refundNo, int amountFen, String reason)
    {
        ready();
        com.wechat.pay.java.service.refund.model.CreateRequest request = new com.wechat.pay.java.service.refund.model.CreateRequest();
        request.setOutTradeNo(tradeNo); request.setOutRefundNo(refundNo); request.setReason(reason);
        com.wechat.pay.java.service.refund.model.AmountReq amount = new com.wechat.pay.java.service.refund.model.AmountReq();
        amount.setTotal((long) amountFen); amount.setRefund((long) amountFen); amount.setCurrency("CNY"); request.setAmount(amount);
        // Signed query reconciliation is durable and does not depend on a callback.
        DiagnosticAttempt diagnostic = new DiagnosticAttempt("refund");
        try { return refundService.create(request); }
        catch (Exception ex) { diagnostic.failure(ex); throw upstream(); }
        finally { diagnostic.close(); }
    }

    @Override
    public com.wechat.pay.java.service.refund.model.Refund queryRefund(String refundNo)
    {
        ready();
        com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest request = new com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest();
        request.setOutRefundNo(refundNo);
        DiagnosticAttempt diagnostic = new DiagnosticAttempt("query_refund");
        try { return refundService.queryByOutRefundNo(request); }
        catch (com.wechat.pay.java.core.exception.ServiceException ex)
        {
            if ("RESOURCE_NOT_EXISTS".equals(ex.getErrorCode())) { diagnostic.record("not_found", ex); return null; }
            diagnostic.failure(ex);
            throw upstream();
        }
        catch (Exception ex) { diagnostic.failure(ex); throw upstream(); }
        finally { diagnostic.close(); }
    }

    @Override
    public Transaction parseNotification(String body, HttpServletRequest request)
    {
        ready();
        try
        {
            String timestamp = request.getHeader("Wechatpay-Timestamp");
            long time = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (time < now - 300 || time > now + 300 || body.length() > 65536) throw new IllegalArgumentException();
            String signType = request.getHeader("Wechatpay-Signature-Type");
            if (signType != null && !"WECHATPAY2-SHA256-RSA2048".equals(signType)) throw new IllegalArgumentException();
            RequestParam param = new RequestParam.Builder().serialNumber(request.getHeader("Wechatpay-Serial"))
                    .nonce(request.getHeader("Wechatpay-Nonce")).signature(request.getHeader("Wechatpay-Signature"))
                    .timestamp(timestamp).body(body).build();
            Transaction result = parser.parse(param, Transaction.class);
            JsonNode envelope = json.readTree(body);
            if (!"TRANSACTION.SUCCESS".equals(envelope.path("event_type").asText())
                    || !"encrypt-resource".equals(envelope.path("resource_type").asText())) throw new IllegalArgumentException();
            return result;
        }
        catch (Exception ex) { throw new QicaiApiException(HttpStatus.UNAUTHORIZED, "支付通知验签或内容校验失败"); }
    }

    private static QicaiApiException upstream()
    {
        return new QicaiApiException(HttpStatus.BAD_GATEWAY, "微信支付服务暂不可用，请查询订单状态后重试");
    }
}

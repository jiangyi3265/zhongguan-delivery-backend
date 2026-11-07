package com.ruoyi.qicai.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.qicai.QicaiApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Real production gateway/RestTemplate, synthetic HTTP transport, no WeChat calls. */
class WechatLoginGatewayTest
{
    @TempDir Path dir;

    private OfficialWechatPaymentGateway loginOnly() throws Exception
    {
        Path secret = dir.resolve("synthetic-app-secret.txt");
        Files.write(secret, "synthetic-app-secret".getBytes(StandardCharsets.UTF_8));
        WechatPayProperties p = new WechatPayProperties(); p.setEnabled(false);
        p.setAppId("wx1234567890abcdef"); p.setAppSecretFile(secret.toString());
        p.setPrivateKeyPath(dir.resolve("does-not-exist.pem").toString());
        OfficialWechatPaymentGateway gateway = new OfficialWechatPaymentGateway(p, new ObjectMapper());
        gateway.initialize(); return gateway;
    }

    @Test void loginWorksWithOnlyAppCredentialsWhilePaymentStaysDisabled() throws Exception
    {
        OfficialWechatPaymentGateway gateway = loginOnly();
        RestTemplate client = (RestTemplate) ReflectionTestUtils.getField(gateway, "wechatLogin");
        assertNotNull(client);
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/sns/jscode2session?")))
                .andExpect(queryParam("appid", "wx1234567890abcdef"))
                .andExpect(queryParam("js_code", "synthetic-code"))
                .andRespond(withSuccess("{\"openid\":\"synthetic-openid\",\"session_key\":\"not-exposed\"}", MediaType.APPLICATION_JSON));
        assertEquals("synthetic-openid", gateway.exchangeCode("synthetic-code"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(QicaiApiException.class,
                () -> gateway.prepay("never-sent", 100, "synthetic-openid", "2099-01-01T00:00:00Z")).getStatus());
        assertNull(ReflectionTestUtils.getField(gateway, "service")); server.verify();
    }

    @Test void missingLoginConfigurationIsControlled503WithoutStartingPayment()
    {
        OfficialWechatPaymentGateway gateway = new OfficialWechatPaymentGateway(new WechatPayProperties(), new ObjectMapper());
        gateway.initialize();
        QicaiApiException failure = assertThrows(QicaiApiException.class, () -> gateway.exchangeCode("fresh-code"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        assertFalse(failure.getMessage().contains("fresh-code"));
    }

    @Test void invalidProviderIdentityAndTransportFailureDoNotBecomeLoginSuccess() throws Exception
    {
        OfficialWechatPaymentGateway gateway = loginOnly();
        MockRestServiceServer server = MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(gateway, "wechatLogin")).build();
        server.expect(anything()).andRespond(withSuccess("{\"errcode\":40029,\"errmsg\":\"synthetic-secret\"}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withServerError());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(QicaiApiException.class, () -> gateway.exchangeCode("invalid")).getStatus());
        QicaiApiException failure = assertThrows(QicaiApiException.class, () -> gateway.exchangeCode("timeout"));
        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatus()); assertNull(failure.getCause()); server.verify();
    }
}

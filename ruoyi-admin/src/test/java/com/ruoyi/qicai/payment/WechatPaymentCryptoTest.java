package com.ruoyi.qicai.payment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import com.ruoyi.common.filter.RepeatedlyRequestWrapper;
import com.ruoyi.qicai.QicaiApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class WechatPaymentCryptoTest
{
    @Test void exactFenRejectsFractionAndOverflow()
    {
        assertEquals(1913,WechatPaymentService.amountFen(new BigDecimal("19.13")));
        assertEquals(0,WechatPaymentService.amountFen(BigDecimal.ZERO));
        assertThrows(QicaiApiException.class,()->WechatPaymentService.amountFen(new BigDecimal("1.001")));
        assertThrows(QicaiApiException.class,()->WechatPaymentService.amountFen(new BigDecimal("21474836.48")));
        assertThrows(QicaiApiException.class,()->WechatPaymentService.amountFen(new BigDecimal("-0.01")));
    }
    @Test void repeatedBodyKeepsUnicodeWhitespaceAndTrailingNewline() throws Exception
    {
        byte[] bytes="{\n  \"note\": \"中文\"\r\n}\n".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request=new MockHttpServletRequest();request.setContent(bytes);
        RepeatedlyRequestWrapper wrapper=new RepeatedlyRequestWrapper(request,new MockHttpServletResponse());
        assertArrayEquals(bytes,org.springframework.util.StreamUtils.copyToByteArray(wrapper.getInputStream()));
        assertArrayEquals(bytes,org.springframework.util.StreamUtils.copyToByteArray(wrapper.getInputStream()));
    }
    @Test void officialSdkVerifiesAndDecryptsMultilineNotification() throws Exception
    {
        PaymentCryptoFixture fixture=new PaymentCryptoFixture();String body=fixture.envelope(fixture.transaction("QC123",1913,"synthetic-owner"));
        com.wechat.pay.java.service.payments.model.Transaction result=fixture.official.parseNotification(body,fixture.signed(body));
        assertEquals(1913,result.getAmount().getTotal());assertEquals("QC123",result.getOutTradeNo());assertEquals("synthetic-owner",result.getPayer().getOpenid());
    }
    @Test void tamperProbeWrongSerialAndExpiredSignatureAreRejected() throws Exception
    {
        PaymentCryptoFixture f=new PaymentCryptoFixture();String body=f.envelope(f.transaction("QC123",1913,"synthetic-owner"));
        MockHttpServletRequest r=f.signed(body);
        assertThrows(QicaiApiException.class,()->f.official.parseNotification(body+" ",r));
        MockHttpServletRequest old=f.signed(body,Instant.now().minusSeconds(301).getEpochSecond());
        assertThrows(QicaiApiException.class,()->f.official.parseNotification(body,old));
        MockHttpServletRequest wrong=f.signed(body);wrong.removeHeader("Wechatpay-Serial");wrong.addHeader("Wechatpay-Serial","PUB_KEY_ID_99999");
        assertThrows(QicaiApiException.class,()->f.official.parseNotification(body,wrong));
        MockHttpServletRequest probe=f.signed(body);probe.removeHeader("Wechatpay-Signature");probe.addHeader("Wechatpay-Signature","WECHATPAY/SIGNTEST/probe");
        assertThrows(QicaiApiException.class,()->f.official.parseNotification(body,probe));
    }
    @Test void enabledConfigurationFailsClosedWhenMissing() throws Exception
    {
        WechatPayProperties p=new WechatPayProperties();OfficialWechatPaymentGateway g=new OfficialWechatPaymentGateway(p,new com.fasterxml.jackson.databind.ObjectMapper());
        g.initialize();assertThrows(QicaiApiException.class,()->g.prepay("QC123",1,"synthetic","2026-12-31T01:30:00Z"));
        p.setEnabled(true);assertThrows(IllegalStateException.class,g::initialize);
    }
    @Test void callbackLimitIsBytesEvenWhenLengthIsUnknown() throws Exception
    {
        MockHttpServletRequest r=new MockHttpServletRequest("POST","/qicai/payment/wechat/notify");r.setServletPath("/qicai/payment/wechat/notify");
        r.setContent(new byte[65536]);assertEquals(65536,new RepeatedlyRequestWrapper(r,new MockHttpServletResponse()).getInputStream().available());
        r.setContent(new byte[65537]);assertThrows(RepeatedlyRequestWrapper.BodyLimitException.class,()->new RepeatedlyRequestWrapper(r,new MockHttpServletResponse()));
        r.setContent(new String(new char[21846]).replace('\0','汉').getBytes(StandardCharsets.UTF_8));
        assertThrows(RepeatedlyRequestWrapper.BodyLimitException.class,()->new RepeatedlyRequestWrapper(r,new MockHttpServletResponse()));
    }
    @Test void malformedPasswordJsonIsControlled400ThroughBothFilters() throws Exception
    {
        MockHttpServletRequest r=new MockHttpServletRequest("PUT","/system/user/profile/updatePwd");r.setServletPath("/system/user/profile/updatePwd");r.setContentType("application/json");
        r.setContent("{\"oldPassword\":\"synthetic\",\"newPassword\":\"synthetic\"}\n<script>trailing</script>".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response=new MockHttpServletResponse();boolean[] reached={false};
        new com.ruoyi.common.filter.XssFilter().doFilter(r,response,(request,res)->new com.ruoyi.common.filter.RepeatableFilter().doFilter(request,res,(wrapped,end)->{reached[0]=true;}));
        assertEquals(400,response.getStatus());assertFalse(reached[0]);assertEquals(400,new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString()).path("code").asInt());assertFalse(response.getContentAsString().contains("synthetic"));
    }
    @Test void xssCleaningCannotTurnPasswordJsonIntoServerError() throws Exception
    {
        for (String markup : new String[]{"<b>x", "<a href='https://example.invalid'>x"})
        {
            MockHttpServletRequest r=new MockHttpServletRequest("POST","/system/user");r.setServletPath("/system/user");r.setContentType("application/json");
            com.fasterxml.jackson.databind.node.ObjectNode body=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            body.put("password","synthetic-secret");body.put("nickName",markup);r.setContent(body.toString().getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response=new MockHttpServletResponse();boolean[] reached={false};
            assertDoesNotThrow(()->new com.ruoyi.common.filter.XssFilter().doFilter(r,response,(request,res)->new com.ruoyi.common.filter.RepeatableFilter().doFilter(request,res,(wrapped,end)->{reached[0]=true;})));
            assertEquals(400,response.getStatus());assertFalse(reached[0]);assertEquals(400,new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString()).path("code").asInt());assertFalse(response.getContentAsString().contains("synthetic-secret"));
        }
    }
}

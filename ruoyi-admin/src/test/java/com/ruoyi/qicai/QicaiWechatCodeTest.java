package com.ruoyi.qicai;

import com.ruoyi.qicai.payment.WechatPayProperties;
import com.ruoyi.qicai.payment.WechatPaymentGateway;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QicaiWechatCodeTest
{
    @Test @SuppressWarnings("unchecked") void unavailableRedisFailsClosedBeforeExternalIdentityExchange()
    {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(),eq("used"),eq(300L),eq(TimeUnit.SECONDS)))
                .thenThrow(new RedisConnectionFailureException("synthetic Redis outage"));
        WechatPaymentGateway provider=mock(WechatPaymentGateway.class);WechatPayProperties config=new WechatPayProperties();
        config.setAppId("wx1234567890abcdef");
        QicaiApiException e=assertThrows(QicaiApiException.class,()->new QicaiWechatCodeService(redis,provider,config).exchange("synthetic-code"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,e.getStatus());assertNull(e.getCause());verifyNoInteractions(provider);
    }

    @Test @SuppressWarnings("unchecked") void providerIdentityMustBeValidAndNoIdentityIsCachedUnderConsumedCode()
    {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);when(values.setIfAbsent(anyString(),eq("used"),eq(300L),eq(TimeUnit.SECONDS))).thenReturn(true,false);
        WechatPaymentGateway provider=mock(WechatPaymentGateway.class);when(provider.exchangeCode("synthetic-code")).thenReturn("invalid identity");
        WechatPayProperties config=new WechatPayProperties();config.setAppId("wx1234567890abcdef");QicaiWechatCodeService codes=new QicaiWechatCodeService(redis,provider,config);
        assertEquals(HttpStatus.BAD_GATEWAY,assertThrows(QicaiApiException.class,()->codes.exchange("synthetic-code")).getStatus());
        assertEquals(HttpStatus.CONFLICT,assertThrows(QicaiApiException.class,()->codes.exchange("synthetic-code")).getStatus());
        verify(provider,times(1)).exchangeCode("synthetic-code");verify(redis,never()).delete(anyString());
        verify(values,times(2)).setIfAbsent("qc:wechat:code:wx1234567890abcdef:"+QicaiAuthService.sha256("synthetic-code"),"used",300,TimeUnit.SECONDS);
    }
}

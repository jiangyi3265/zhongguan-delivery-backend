package com.ruoyi.qicai;

import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.ruoyi.qicai.payment.WechatPayProperties;
import com.ruoyi.qicai.payment.WechatPaymentGateway;

/** Shared one-time-code boundary for login, registration and payment identity binding. */
@Service
public class QicaiWechatCodeService
{
    private final StringRedisTemplate redis;
    private final WechatPaymentGateway gateway;
    private final WechatPayProperties config;

    public QicaiWechatCodeService(StringRedisTemplate redis, WechatPaymentGateway gateway, WechatPayProperties config)
    {
        this.redis = redis; this.gateway = gateway; this.config = config;
    }

    public String exchange(String code)
    {
        if (!config.getAppId().matches("wx[0-9a-fA-F]{16}"))
            throw new QicaiApiException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录尚未配置");
        Boolean claimed;
        try
        {
            claimed = redis.opsForValue().setIfAbsent("qc:wechat:code:" + config.getAppId() + ":" + QicaiAuthService.sha256(code),
                    "used", 300, TimeUnit.SECONDS);
        }
        catch (org.springframework.dao.DataAccessException ex)
        {
            throw new QicaiApiException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录暂不可用，请稍后重新授权");
        }
        if (!Boolean.TRUE.equals(claimed))
            throw new QicaiApiException(HttpStatus.CONFLICT, "微信登录凭证已使用，请重新授权");
        // Keep the claim even after provider/DB failure: the provider may have consumed
        // the code. Never cache OpenID under this code or return a previous session.
        String openId = gateway.exchangeCode(code);
        if (openId == null || !openId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new QicaiApiException(HttpStatus.BAD_GATEWAY, "微信登录返回无效，请重新授权");
        return openId;
    }
}

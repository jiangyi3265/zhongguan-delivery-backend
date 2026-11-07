package com.ruoyi.qicai.payment;

import com.ruoyi.qicai.QicaiApiException;
import org.springframework.http.HttpStatus;

/** Only explicit provider rejections; never used for timeouts or ambiguous outcomes. */
public class WechatPrepayRejectedException extends QicaiApiException
{
    public WechatPrepayRejectedException() { super(HttpStatus.BAD_GATEWAY, "微信拒绝本次下单，请核对支付配置后重试"); }
}

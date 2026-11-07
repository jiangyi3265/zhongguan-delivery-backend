package com.ruoyi.qicai.payment;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import com.wechat.pay.java.service.payments.model.Transaction;

public interface WechatPaymentGateway
{
    String exchangeCode(String code);
    Map<String, Object> prepay(String tradeNo, int amountFen, String openId, String expiresAt);
    Transaction query(String tradeNo);
    void close(String tradeNo);
    com.wechat.pay.java.service.refund.model.Refund refund(String tradeNo, String refundNo, int amountFen, String reason);
    com.wechat.pay.java.service.refund.model.Refund queryRefund(String refundNo);
    Transaction parseNotification(String body, HttpServletRequest request);
}

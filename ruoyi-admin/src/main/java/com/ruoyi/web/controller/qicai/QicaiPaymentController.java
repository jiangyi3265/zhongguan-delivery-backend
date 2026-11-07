package com.ruoyi.web.controller.qicai;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.qicai.QicaiAuthService;
import com.ruoyi.qicai.payment.WechatPaymentService;

@RestController
public class QicaiPaymentController
{
    private final QicaiAuthService auth;
    private final WechatPaymentService payments;
    public QicaiPaymentController(QicaiAuthService auth, WechatPaymentService payments) { this.auth = auth; this.payments = payments; }

    @Anonymous
    @PostMapping("/qicai/mobile/payments/wechat/identity")
    public AjaxResult bind(@RequestBody Map<String, Object> body, HttpServletRequest request)
    { return AjaxResult.success(payments.bind(auth.requireCustomer(request), body)); }

    @Anonymous
    @PostMapping("/qicai/mobile/orders/{id}/wechat-prepay")
    public AjaxResult prepay(@PathVariable long id, HttpServletRequest request)
    { return AjaxResult.success(payments.prepay(auth.requireCustomer(request), id)); }

    @Anonymous
    @GetMapping("/qicai/mobile/orders/{id}/payment")
    public AjaxResult status(@PathVariable long id, HttpServletRequest request)
    { return AjaxResult.success(payments.status(auth.requireCustomer(request), id)); }

    // Only this endpoint accepts provider callbacks without a customer token;
    // the official SDK verifies signatures and decrypts before any state change.
    @PostMapping(value = "/qicai/payment/wechat/notify", consumes = "application/json")
    public ResponseEntity<Void> notify(@RequestBody byte[] body, HttpServletRequest request)
    { payments.notifyPaid(new String(body, java.nio.charset.StandardCharsets.UTF_8), request); return ResponseEntity.noContent().build(); }
}

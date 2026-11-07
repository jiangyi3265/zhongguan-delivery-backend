package com.ruoyi.qicai.payment;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.qicai.QicaiApiException;
import com.ruoyi.qicai.QicaiAuthService;
import com.ruoyi.qicai.QicaiAuthService.Principal;
import com.wechat.pay.java.service.payments.model.Transaction;

@Service
public class WechatPaymentService
{
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.qicai.QicaiNoticeService notices;
    private final JdbcTemplate jdbc;
    private final WechatPayProperties config;
    private final WechatPaymentGateway gateway;
    private final com.ruoyi.qicai.QicaiWechatCodeService codes;
    private final TransactionTemplate tx;

    public WechatPaymentService(JdbcTemplate jdbc, WechatPayProperties config, WechatPaymentGateway gateway,
            org.springframework.transaction.PlatformTransactionManager manager, com.ruoyi.qicai.QicaiWechatCodeService codes)
    {
        this.jdbc = jdbc; this.config = config; this.gateway = gateway; this.tx = new TransactionTemplate(manager);
        this.codes = codes;
    }

    public boolean isEnabled() { return config.isEnabled(); }

    public static int amountFen(BigDecimal payable)
    {
        try
        {
            int amount = payable.movePointRight(2).intValueExact();
            if (amount < 0) throw new ArithmeticException();
            return amount;
        }
        catch (ArithmeticException ex) { throw error(HttpStatus.BAD_REQUEST, "订单金额超出微信支付范围或精度不正确"); }
    }

    private void enabled()
    {
        if (!isEnabled()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "微信支付尚未启用");
    }

    public Map<String, Object> bind(Principal principal, Map<String, Object> body)
    {
        enabled();
        String code = QicaiAuthService.required(body, "code", 256);
        if (body.containsKey("openid") || body.containsKey("openId")) throw error(HttpStatus.BAD_REQUEST, "请使用微信登录凭证绑定");
        String openId = codes.exchange(code);
        return tx.execute(state -> {
            List<Map<String, Object>> customers = jdbc.queryForList("SELECT status FROM qc_customer WHERE id=? AND deleted=0 FOR UPDATE", principal.getId());
            if (customers.isEmpty() || !"ACTIVE".equals(customers.get(0).get("status")))
                throw error(HttpStatus.FORBIDDEN, "客户未通过审核或已停用");
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT open_id FROM qc_wechat_identity WHERE customer_id=? AND app_id=?",
                    principal.getId(), config.getAppId());
            if (!rows.isEmpty())
            {
                if (!openId.equals(rows.get(0).get("open_id"))) throw error(HttpStatus.CONFLICT, "当前账号已绑定其他微信身份");
            }
            else jdbc.update("INSERT INTO qc_wechat_identity(customer_id,app_id,open_id) VALUES(?,?,?)",
                    principal.getId(), config.getAppId(), openId);
            Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("bound", true); return result;
        });
    }

    public Map<String, Object> prepay(Principal principal, long orderId)
    {
        enabled();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Prepayment requires independent sequential transaction boundaries");
        // Commit the attempt BEFORE calling the provider. Timeout is an unknown outcome,
        // not proof that no remote order exists; cancellation must still close this trade.
        tx.execute(state -> {
            lockActiveCustomer(principal);
            Map<String, Object> order = ownedOrder(principal, orderId, true);
            requireWithinPaymentDeadline(orderId);
            if (!"UNPAID".equals(order.get("payment_status")) || !"PENDING_PAYMENT".equals(order.get("status")))
                throw error(HttpStatus.CONFLICT, "订单当前不需要支付，请刷新订单");
            List<Map<String, Object>> identities = jdbc.queryForList("SELECT open_id FROM qc_wechat_identity WHERE customer_id=? AND app_id=?",
                    principal.getId(), config.getAppId());
            if (identities.isEmpty()) throw error(HttpStatus.CONFLICT, "请先授权微信身份");
            // The locked order serializes same-order creation; do not gap-lock missing payment rows.
            if (payment(orderId, false) == null)
                jdbc.update("INSERT INTO qc_payment(order_id,out_trade_no,merchant_id,app_id,payer_open_id,amount_fen,status,uncertain_attempts) VALUES(?,?,?,?,?,?,'PREPARED',0)",
                        orderId, order.get("order_no"), config.getMerchantId(), config.getAppId(), identities.get(0).get("open_id"),
                        amountFen((BigDecimal) order.get("payable_amount")));
            return null;
        });
        // Commit the durable intent with this transaction's existing connection.
        // Do not borrow REQUIRES_NEW while holding a bounded connection pool.
        tx.execute(state -> {
            lockActiveCustomer(principal);
            Map<String, Object> order = ownedOrder(principal, orderId, true);
            requireWithinPaymentDeadline(orderId);
            Map<String, Object> prepared = payment(orderId, false);
            if (!"PENDING_PAYMENT".equals(order.get("status")) || !"UNPAID".equals(order.get("payment_status"))
                    || prepared == null || !("PENDING".equals(prepared.get("status")) || "PREPARED".equals(prepared.get("status")) || "REJECTED".equals(prepared.get("status"))))
                throw error(HttpStatus.CONFLICT, "订单当前不需要支付，请刷新订单");
            assertConfiguration(prepared);
            jdbc.update("UPDATE qc_payment SET status='PENDING',uncertain_attempts=uncertain_attempts+1 WHERE order_id=?", orderId);
            return null;
        });
        final boolean[] sent = { false };
        try { return tx.execute(state -> {
            lockActiveCustomer(principal);
            Map<String,Object> order = ownedOrder(principal, orderId, true);
            requireWithinPaymentDeadline(orderId);
            Map<String, Object> payment = payment(orderId, true);
            if (!"PENDING_PAYMENT".equals(order.get("status")) || !"UNPAID".equals(order.get("payment_status"))
                    || payment == null || !"PENDING".equals(payment.get("status")))
                throw error(HttpStatus.CONFLICT, "订单当前不需要支付，请刷新订单");
            assertConfiguration(payment);
            // Serialize prepay against close/callback via the order row; never create a
            // payable remote trade after local cancellation has restored stock/coupon.
            String expiresAt = java.time.Instant.ofEpochSecond(jdbc.queryForObject("SELECT UNIX_TIMESTAMP(DATE_ADD(created_at,INTERVAL 30 MINUTE)) FROM qc_order WHERE id=?", Long.class, orderId))
                    .atOffset(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            sent[0] = true;
            return gateway.prepay(text(payment, "out_trade_no"), ((Number) payment.get("amount_fen")).intValue(), text(payment, "payer_open_id"), expiresAt);
        }); }
        catch (RuntimeException ex)
        {
            if (!sent[0] || ex instanceof WechatPrepayRejectedException)
            {
                try { tx.execute(state -> {
                    ownedOrder(principal, orderId, true);
                    // Resolve only this request's known unsent/rejected intent.
                    // Earlier unknown outcomes remain reserved and queryable.
                    jdbc.update("UPDATE qc_payment SET status=CASE WHEN uncertain_attempts=1 THEN 'REJECTED' ELSE status END,uncertain_attempts=uncertain_attempts-1 WHERE order_id=? AND status='PENDING' AND uncertain_attempts>0", orderId);
                    return null;
                }); }
                catch (RuntimeException cleanupFailure)
                {
                    org.slf4j.LoggerFactory.getLogger(WechatPaymentService.class).warn(
                            "Payment intent reconciliation deferred orderId={} type={}", orderId, cleanupFailure.getClass().getSimpleName());
                }
            }
            throw ex;
        }
    }

    public Map<String, Object> status(Principal principal, long orderId)
    {
        return tx.execute(state -> {
            Map<String, Object> order = ownedOrder(principal, orderId, true);
            Map<String, Object> payment = payment(orderId, true);
            if (payment != null && "PENDING".equals(payment.get("status")))
            {
                enabled(); assertConfiguration(payment);
                Transaction transaction = gateway.query(text(payment, "out_trade_no"));
                if (transaction != null && Transaction.TradeStateEnum.SUCCESS == transaction.getTradeState())
                {
                    applyPaid(order, payment, transaction);
                    order = ownedOrder(principal, orderId, false);
                }
                else if (transaction != null && Transaction.TradeStateEnum.CLOSED == transaction.getTradeState())
                {
                    applyClosed(orderId, payment, transaction);
                    order = ownedOrder(principal, orderId, false);
                }
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("orderId", orderId); result.put("orderStatus", order.get("status"));
            result.put("paymentStatus", order.get("payment_status")); result.put("payableAmount", order.get("payable_amount"));
            return result;
        });
    }

    private void applyClosed(long orderId, Map<String,Object> payment, Transaction transaction)
    {
                    if (!text(payment, "merchant_id").equals(transaction.getMchid()) || !text(payment, "app_id").equals(transaction.getAppid())
                            || !text(payment, "out_trade_no").equals(transaction.getOutTradeNo())) throw error(HttpStatus.CONFLICT, "关单结果与订单不一致");
                    jdbc.update("UPDATE qc_payment SET status='CLOSED' WHERE order_id=?", orderId);
                    jdbc.update("UPDATE qc_order SET payment_status='CLOSED' WHERE id=?", orderId);
                    releaseCancelledOrder(orderId);
    }

    public void notifyPaid(String rawBody, HttpServletRequest request)
    {
        enabled();
        Transaction transaction = gateway.parseNotification(rawBody, request);
        tx.execute(state -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM qc_order WHERE order_no=? AND deleted=0 FOR UPDATE", transaction.getOutTradeNo());
            if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "支付订单不存在");
            Map<String, Object> order = rows.get(0);
            Map<String, Object> payment = payment(((Number) order.get("id")).longValue(), true);
            if (payment == null) throw error(HttpStatus.CONFLICT, "支付订单尚未初始化");
            applyPaid(order, payment, transaction);
            return null;
        });
    }

    // Called INSIDE QicaiService.cancelOrder's transaction, with the order row locked.
    public void closeBeforeCancel(long orderId, String paymentStatus)
    {
        if ("PAID".equals(paymentStatus)) throw error(HttpStatus.CONFLICT, "已付款订单需要退款处理，不能直接取消");
        if (!"UNPAID".equals(paymentStatus)) return;
        Map<String, Object> payment = payment(orderId, true);
        if (payment != null)
        {
            if ("PENDING".equals(payment.get("status"))) { enabled(); assertConfiguration(payment); gateway.close(text(payment, "out_trade_no")); }
            else if (!"PREPARED".equals(payment.get("status")) && !"REJECTED".equals(payment.get("status")) && !"CLOSED".equals(payment.get("status")))
                throw error(HttpStatus.CONFLICT, "支付状态不允许取消");
            jdbc.update("UPDATE qc_payment SET status='CLOSED' WHERE order_id=?", orderId);
        }
        jdbc.update("UPDATE qc_order SET payment_status='CLOSED' WHERE id=?", orderId);
    }

    /** Caller owns the order lock. Confirmed closure/legacy cancellation only. */
    public void releaseCancelledOrder(long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status,payment_status FROM qc_order WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty() || "CANCELLED".equals(rows.get(0).get("status"))) return;
        String status = String.valueOf(rows.get(0).get("status"));
        if (!("PENDING_PAYMENT".equals(status) || "PENDING_SHIPMENT".equals(status))
                || !java.util.Arrays.asList("NOT_REQUIRED", "NO_PAYMENT_REQUIRED", "CLOSED").contains(text(rows.get(0), "payment_status")))
            throw error(HttpStatus.CONFLICT, "订单当前不能释放库存");
        com.ruoyi.qicai.QicaiInventory.restoreOrder(jdbc, id);
        jdbc.update("UPDATE qc_customer_coupon SET status='AVAILABLE',used_order_id=NULL,used_at=NULL WHERE used_order_id=? AND status='USED'", id);
        jdbc.update("UPDATE qc_order SET status='CANCELLED',version=version+1 WHERE id=?", id);
        if(notices!=null)notices.orderEvent(id,"CANCELLED");
    }

    private void requireWithinPaymentDeadline(long orderId)
    {
        Boolean expired = jdbc.queryForObject("SELECT NOW()>=DATE_ADD(created_at,INTERVAL 30 MINUTE) FROM qc_order WHERE id=?", Boolean.class, orderId);
        if (Boolean.TRUE.equals(expired)) throw error(HttpStatus.CONFLICT, "订单已过30分钟支付期限，正在确认关闭，请重新下单");
    }

    /** One order transaction; provider failures keep both stock and coupons reserved. */
    public String expireUnpaid(long orderId)
    {
        try { return tx.execute(state -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT *,NOW()>=DATE_ADD(created_at,INTERVAL 30 MINUTE) AS expired FROM qc_order WHERE id=? AND deleted=0 FOR UPDATE", orderId);
            if (rows.isEmpty()) return "SKIPPED";
            Map<String, Object> order = rows.get(0);
            if (!"PENDING_PAYMENT".equals(order.get("status")) || !"UNPAID".equals(order.get("payment_status"))
                    || !Boolean.TRUE.equals(order.get("expired")) && !"1".equals(String.valueOf(order.get("expired")))) return "SKIPPED";
            Map<String, Object> payment = payment(orderId, true);
            if (payment != null && "PENDING".equals(payment.get("status")))
            {
                enabled(); assertConfiguration(payment);
                Transaction remote = gateway.query(text(payment, "out_trade_no"));
                if (remote != null && Transaction.TradeStateEnum.SUCCESS == remote.getTradeState())
                {
                    applyPaid(order, payment, remote); recordExpiry(orderId, "PAID", null); return "PAID";
                }
                if (remote != null && Transaction.TradeStateEnum.CLOSED == remote.getTradeState())
                {
                    applyClosed(orderId,payment,remote);recordExpiry(orderId,"CLOSED",null);return "CLOSED";
                }
                // Even a missing query result cannot establish that an earlier timed-out
                // prepay will never succeed. The close call must confirm closure.
            }
            closeBeforeCancel(orderId, "UNPAID"); releaseCancelledOrder(orderId);
            recordExpiry(orderId, "CLOSED", null); return "CLOSED";
        }); }
        catch (RuntimeException ex)
        {
            recordExpiry(orderId, "RETRY", "关单或结果核验未完成，保留库存与优惠券，等待重试");
            return "RETRY";
        }
    }

    private void recordExpiry(long id, String status, String error)
    {
        jdbc.update("INSERT INTO qc_payment_maintenance(order_id,status,attempts,last_error,checked_at,next_attempt_at) VALUES(?,?,1,?,NOW(),CASE WHEN ?='RETRY' THEN DATE_ADD(NOW(),INTERVAL 1 MINUTE) ELSE NULL END) ON DUPLICATE KEY UPDATE status=VALUES(status),attempts=attempts+1,last_error=VALUES(last_error),checked_at=NOW(),next_attempt_at=VALUES(next_attempt_at)", id,status,error,status);
    }

    public int expireDueOrders()
    {
        if (!isEnabled()) return 0;
        List<Long> ids = jdbc.queryForList("SELECT o.id FROM qc_order o LEFT JOIN qc_payment_maintenance m ON m.order_id=o.id WHERE o.deleted=0 AND o.status='PENDING_PAYMENT' AND o.payment_status='UNPAID' AND o.created_at<=DATE_SUB(NOW(),INTERVAL 30 MINUTE) AND (m.next_attempt_at IS NULL OR m.next_attempt_at<=NOW()) ORDER BY COALESCE(m.checked_at,o.created_at),o.id LIMIT 50", Long.class);
        int retry = 0;
        for (Long id : ids) { try { if ("RETRY".equals(expireUnpaid(id))) retry++; } catch (RuntimeException ex) { retry++; org.slf4j.LoggerFactory.getLogger(WechatPaymentService.class).warn("Payment expiry persistence failed orderId={} type={}", id, ex.getClass().getSimpleName()); } }
        return retry;
    }

    private void lockActiveCustomer(Principal principal)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status FROM qc_customer WHERE id=? AND deleted=0 FOR UPDATE", principal.getId());
        if (rows.isEmpty() || !"ACTIVE".equals(rows.get(0).get("status"))) throw error(HttpStatus.FORBIDDEN, "客户未通过审核或已停用");
    }

    private void applyPaid(Map<String, Object> order, Map<String, Object> payment, Transaction transaction)
    {
        if (transaction == null || Transaction.TradeStateEnum.SUCCESS != transaction.getTradeState()
                || Transaction.TradeTypeEnum.JSAPI != transaction.getTradeType()
                || !text(payment, "merchant_id").equals(transaction.getMchid())
                || !text(payment, "app_id").equals(transaction.getAppid())
                || !text(payment, "out_trade_no").equals(transaction.getOutTradeNo())
                || transaction.getAmount() == null || !"CNY".equals(transaction.getAmount().getCurrency())
                || !Integer.valueOf(((Number) payment.get("amount_fen")).intValue()).equals(transaction.getAmount().getTotal())
                || amountFen((BigDecimal) order.get("payable_amount")) != ((Number) payment.get("amount_fen")).intValue()
                || transaction.getPayer() == null || !text(payment, "payer_open_id").equals(transaction.getPayer().getOpenid())
                || transaction.getTransactionId() == null || !transaction.getTransactionId().matches("[0-9A-Za-z]{1,64}"))
            throw error(HttpStatus.CONFLICT, "支付结果与订单不一致");
        Timestamp success;
        try { success = Timestamp.from(OffsetDateTime.parse(transaction.getSuccessTime()).toInstant()); }
        catch (RuntimeException ex) { throw error(HttpStatus.CONFLICT, "支付时间无效"); }
        if ("PAID".equals(payment.get("status")))
        {
            if (!transaction.getTransactionId().equals(payment.get("transaction_id"))) throw error(HttpStatus.CONFLICT, "支付流水不一致");
            return; // verified duplicate: do not touch version, coupon, stock or events
        }
        if (!"PENDING".equals(payment.get("status")) || !"UNPAID".equals(order.get("payment_status"))
                || !"PENDING_PAYMENT".equals(order.get("status"))) throw error(HttpStatus.CONFLICT, "订单支付状态冲突，需要核查");
        jdbc.update("UPDATE qc_payment SET status='PAID',transaction_id=?,paid_at=? WHERE order_id=? AND status='PENDING'",
                transaction.getTransactionId(), success, payment.get("order_id"));
        jdbc.update("UPDATE qc_order SET payment_status='PAID',status='PENDING_SHIPMENT',version=version+1 WHERE id=? AND payment_status='UNPAID'",
                order.get("id"));
        if(notices!=null)notices.orderEvent(((Number)order.get("id")).longValue(),"PAID");
    }

    private void assertConfiguration(Map<String, Object> payment)
    {
        if (!config.getMerchantId().equals(payment.get("merchant_id")) || !config.getAppId().equals(payment.get("app_id")))
            throw error(HttpStatus.CONFLICT, "订单支付账户与当前配置不一致，需要核查");
    }

    private Map<String, Object> ownedOrder(Principal principal, long id, boolean lock)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM qc_order WHERE id=? AND customer_id=? AND deleted=0" + (lock ? " FOR UPDATE" : ""), id, principal.getId());
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "订单不存在");
        return rows.get(0);
    }
    private Map<String, Object> payment(long id, boolean lock)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM qc_payment WHERE order_id=?" + (lock ? " FOR UPDATE" : ""), id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private static String text(Map<String, Object> value, String key) { return String.valueOf(value.get(key)); }
    private static QicaiApiException error(HttpStatus status, String message) { return new QicaiApiException(status, message); }
}

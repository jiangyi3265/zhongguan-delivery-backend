package com.ruoyi.qicai.payment;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.qicai.QicaiApiException;
import com.ruoyi.qicai.QicaiAuthService;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.Status;

/** Full refunds for paid, unassigned orders. Payment collection remains immutable. */
@Service
public class WechatRefundService
{
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.qicai.QicaiNoticeService notices;
    private final JdbcTemplate jdbc;
    private final WechatPayProperties config;
    private final WechatPaymentGateway gateway;
    private final TransactionTemplate tx;
    public WechatRefundService(JdbcTemplate jdbc, WechatPayProperties config, WechatPaymentGateway gateway,
            org.springframework.transaction.PlatformTransactionManager manager)
    { this.jdbc=jdbc; this.config=config; this.gateway=gateway; this.tx=new TransactionTemplate(manager); }

    public Map<String,Object> request(long id, long actor, String name, Map<String,Object> body)
    {
        enabled(); String reason=QicaiAuthService.required(body,"reason",80);
        if (reason.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>80)
            throw error(HttpStatus.BAD_REQUEST,"退款原因最多80字节，请简要填写");
        if (body.containsKey("amount") || body.containsKey("amountFen") || body.containsKey("outRefundNo"))
            throw error(HttpStatus.BAD_REQUEST,"退款金额和退款单号由服务器生成");
        return tx.execute(state->{
            Map<String,Object> order=order(id); Map<String,Object> existing=refund(id);
            if (existing!=null && !Arrays.asList("REJECTED","CLOSED").contains(text(existing,"status")))
            {
                if (!reason.equals(existing.get("reason"))) throw error(HttpStatus.CONFLICT,"已有退款申请，请查看当前记录");
                return detail(id);
            }
            if (!"PENDING_SHIPMENT".equals(order.get("status")) || !"PAID".equals(order.get("payment_status")))
                throw error(HttpStatus.CONFLICT,"当前仅支持已付款且未指派配送订单的全额退款");
            Map<String,Object> payment=paidPayment(id); configuration(payment);
            if (WechatPaymentService.amountFen((java.math.BigDecimal)order.get("payable_amount"))!=((Number)payment.get("amount_fen")).intValue())
                throw error(HttpStatus.CONFLICT,"订单金额与支付记录不一致，需要核查");
            String number="RF"+UUID.randomUUID().toString().replace("-","");
            jdbc.update("INSERT INTO qc_refund(order_id,out_refund_no,amount_fen,status,reason,requested_by,requested_name) VALUES(?,?,?,'REQUESTED',?,?,?) ON DUPLICATE KEY UPDATE out_refund_no=VALUES(out_refund_no),refund_id=NULL,status='REQUESTED',reason=VALUES(reason),requested_by=VALUES(requested_by),requested_name=VALUES(requested_name),requested_at=NOW(),reviewed_by=NULL,reviewed_name=NULL,review_note=NULL,reviewed_at=NULL,success_at=NULL,attempts=0,last_error=NULL,next_attempt_at=NULL",id,number,payment.get("amount_fen"),reason,actor,name);
            jdbc.update("UPDATE qc_order SET payment_status='REFUND_REVIEW',version=version+1 WHERE id=?",id);
            event(id,"REQUESTED",actor,name,"申请全额退款："+reason); return detail(id);
        });
    }

    public Map<String,Object> review(long id,long actor,String name,Map<String,Object> body)
    {
        enabled(); Object approved=body.get("approved");
        if (!(approved instanceof Boolean)) throw error(HttpStatus.BAD_REQUEST,"approved必须为布尔值");
        String number=QicaiAuthService.required(body,"outRefundNo",64);
        String note=QicaiAuthService.required(body,"note",180);
        tx.execute(state->{
            Map<String,Object> order=order(id),refund=requiredRefund(id);
            if (!number.equals(refund.get("out_refund_no"))) throw error(HttpStatus.CONFLICT,"退款申请已变化，请刷新");
            if (!"REQUESTED".equals(refund.get("status")))
            {
                if (Boolean.TRUE.equals(approved) && Arrays.asList("APPROVED","PROCESSING","SUCCESS").contains(text(refund,"status"))) return null;
                if (Boolean.FALSE.equals(approved) && "REJECTED".equals(refund.get("status"))) return null;
                throw error(HttpStatus.CONFLICT,"当前退款状态不能修改审核结果");
            }
            if (!"PENDING_SHIPMENT".equals(order.get("status")) || !"REFUND_REVIEW".equals(order.get("payment_status")))
                throw error(HttpStatus.CONFLICT,"订单状态已变化，不能审核退款");
            String next=Boolean.TRUE.equals(approved)?"APPROVED":"REJECTED";
            jdbc.update("UPDATE qc_refund SET status=?,reviewed_by=?,reviewed_name=?,review_note=?,reviewed_at=NOW(),next_attempt_at=CASE WHEN ?='APPROVED' THEN NOW() ELSE NULL END WHERE order_id=?",next,actor,name,note,next,id);
            jdbc.update("UPDATE qc_order SET payment_status=?,version=version+1 WHERE id=?",Boolean.TRUE.equals(approved)?"REFUNDING":"PAID",id);
            event(id,next,actor,name,note); return null;
        });
        // Approval is committed before any remote effect. A lost response must remain queryable.
        return Boolean.TRUE.equals(approved)?refresh(id):detail(id);
    }

    public Map<String,Object> refresh(long id)
    {
        enabled();
        boolean pending=Boolean.TRUE.equals(tx.execute(state->{
            order(id); Map<String,Object> refund=requiredRefund(id);
            if (!Arrays.asList("APPROVED","PROCESSING","ABNORMAL").contains(text(refund,"status"))) return false;
            if ("APPROVED".equals(refund.get("status"))) event(id,"PROCESSING",null,null,"审核已通过，开始查询并提交同一退款单");
            jdbc.update("UPDATE qc_refund SET status=CASE WHEN status='ABNORMAL' THEN status ELSE 'PROCESSING' END,attempts=attempts+1,next_attempt_at=DATE_ADD(NOW(),INTERVAL 1 MINUTE) WHERE order_id=?",id);
            return true;
        }));
        if (!pending) return detail(id);
        try { return tx.execute(state->{
            Map<String,Object> order=order(id),refund=requiredRefund(id);
            if (!Arrays.asList("PROCESSING","ABNORMAL").contains(text(refund,"status"))) return detail(id);
            Map<String,Object> payment=paidPayment(id);configuration(payment);
            if (!"PENDING_SHIPMENT".equals(order.get("status")) || !"REFUNDING".equals(order.get("payment_status")))
                throw error(HttpStatus.CONFLICT,"退款中的订单状态异常，需要核查");
            Refund remote=gateway.queryRefund(text(refund,"out_refund_no"));
            if (remote==null && "ABNORMAL".equals(refund.get("status"))) throw error(HttpStatus.CONFLICT,"异常退款记录未查到，请核查原退款单");
            if (remote==null) remote=gateway.refund(text(payment,"out_trade_no"),text(refund,"out_refund_no"),((Number)refund.get("amount_fen")).intValue(),text(refund,"reason"));
            apply(id,order,payment,refund,remote);return detail(id);
        }); }
        catch(RuntimeException ex)
        {
            // Never log SDK exceptions: they may contain authorization or account data.
            tx.execute(state->{
                order(id);Map<String,Object> refund=requiredRefund(id);
                if (Arrays.asList("PROCESSING","ABNORMAL").contains(text(refund,"status")))
                {
                    jdbc.update("UPDATE qc_refund SET last_error='退款结果暂未确认，等待查询重试',next_attempt_at=DATE_ADD(NOW(),INTERVAL 1 MINUTE) WHERE order_id=?",id);
                    event(id,text(refund,"status"),null,null,"渠道请求或结果核验失败，保持原退款单号和资源，等待查询");
                }
                return null;
            });
            return detail(id);
        }
    }

    private void apply(long id,Map<String,Object> order,Map<String,Object> payment,Map<String,Object> refund,Refund remote)
    {
        long amount=((Number)refund.get("amount_fen")).longValue();
        if (remote==null || remote.getStatus()==null || remote.getRefundId()==null || !remote.getRefundId().matches("[A-Za-z0-9]{1,64}")
                || !text(refund,"out_refund_no").equals(remote.getOutRefundNo()) || !text(payment,"out_trade_no").equals(remote.getOutTradeNo())
                || !text(payment,"transaction_id").equals(remote.getTransactionId()) || remote.getAmount()==null
                || !Long.valueOf(amount).equals(remote.getAmount().getRefund()) || !Long.valueOf(amount).equals(remote.getAmount().getTotal())
                || !"CNY".equals(remote.getAmount().getCurrency()) || amount!=((Number)payment.get("amount_fen")).longValue()
                || amount!=WechatPaymentService.amountFen((java.math.BigDecimal)order.get("payable_amount"))
                || refund.get("refund_id")!=null && !refund.get("refund_id").equals(remote.getRefundId()))
            throw error(HttpStatus.CONFLICT,"退款结果与原订单不一致，需要核查");
        String status=remote.getStatus().name(); Timestamp success=null;
        if (remote.getStatus()==Status.SUCCESS)
        {
            try {success=Timestamp.from(OffsetDateTime.parse(remote.getSuccessTime()).toInstant());}
            catch(RuntimeException ex){throw error(HttpStatus.CONFLICT,"退款完成时间无效");}
            com.ruoyi.qicai.QicaiInventory.restoreOrder(jdbc,id);
            jdbc.update("UPDATE qc_customer_coupon SET status='AVAILABLE',used_order_id=NULL,used_at=NULL WHERE used_order_id=? AND status='USED'",id);
            jdbc.update("UPDATE qc_order SET status='CANCELLED',payment_status='REFUNDED',version=version+1 WHERE id=?",id);
        }
        else if (remote.getStatus()==Status.CLOSED)
            jdbc.update("UPDATE qc_order SET payment_status='PAID',version=version+1 WHERE id=?",id);
        jdbc.update("UPDATE qc_refund SET status=?,refund_id=?,success_at=?,last_error=?,next_attempt_at=CASE WHEN ? IN ('PROCESSING','ABNORMAL') THEN DATE_ADD(NOW(),INTERVAL 1 MINUTE) ELSE NULL END WHERE order_id=?",status,remote.getRefundId(),success,remote.getStatus()==Status.ABNORMAL?"渠道退款异常，请运营核查，未回补库存和优惠券":null,status,id);
        if (!status.equals(refund.get("status"))) event(id,status,null,null,"微信已确认退款状态："+status+"，渠道退款号："+remote.getRefundId());
    }

    public Map<String,Object> detail(long id)
    {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT order_id AS orderId,out_refund_no AS outRefundNo,refund_id AS refundId,amount_fen AS amountFen,status,reason,requested_by AS requestedBy,requested_name AS requestedName,reviewed_by AS reviewedBy,reviewed_name AS reviewedName,review_note AS reviewNote,requested_at AS requestedAt,reviewed_at AS reviewedAt,success_at AS successAt,attempts,last_error AS lastError,next_attempt_at AS nextAttemptAt FROM qc_refund WHERE order_id=?",id);
        if(rows.isEmpty()) return Collections.emptyMap();
        Map<String,Object> result=rows.get(0);result.put("events",jdbc.queryForList("SELECT id,status,actor_id AS actorId,actor_name AS actorName,note,created_at AS createdAt FROM qc_refund_event WHERE order_id=? ORDER BY id",id));return result;
    }
    public int refreshDueRefunds()
    {
        if(!config.isEnabled())return 0;
        List<Long> ids=jdbc.queryForList("SELECT order_id FROM qc_refund WHERE status IN ('APPROVED','PROCESSING','ABNORMAL') AND next_attempt_at<=NOW() ORDER BY next_attempt_at,order_id LIMIT 50",Long.class);
        int unresolved=0;for(Long id:ids){try{if(refresh(id).get("lastError")!=null)unresolved++;}catch(RuntimeException ex){unresolved++;org.slf4j.LoggerFactory.getLogger(WechatRefundService.class).warn("Refund maintenance persistence failed orderId={} type={}",id,ex.getClass().getSimpleName());}}return unresolved;
    }
    private Map<String,Object> order(long id){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM qc_order WHERE id=? AND deleted=0 FOR UPDATE",id);if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"订单不存在");return rows.get(0);}
    // The order row is always locked before this read; avoiding absent-row gap locks permits independent orders to create refunds concurrently.
    private Map<String,Object> refund(long id){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM qc_refund WHERE order_id=?",id);return rows.isEmpty()?null:rows.get(0);}
    private Map<String,Object> requiredRefund(long id){Map<String,Object> value=refund(id);if(value==null)throw error(HttpStatus.NOT_FOUND,"退款申请不存在");return value;}
    private Map<String,Object> paidPayment(long id){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM qc_payment WHERE order_id=? AND status='PAID' FOR UPDATE",id);if(rows.isEmpty())throw error(HttpStatus.CONFLICT,"没有可退款的已收款记录");return rows.get(0);}
    private void configuration(Map<String,Object> p){if(!config.getAppId().equals(p.get("app_id"))||!config.getMerchantId().equals(p.get("merchant_id")))throw error(HttpStatus.CONFLICT,"原支付账户与当前配置不一致");}
    private void enabled(){if(!config.isEnabled())throw error(HttpStatus.SERVICE_UNAVAILABLE,"微信支付尚未启用");}
    private void event(long id,String status,Long actor,String name,String note){String number=jdbc.queryForObject("SELECT out_refund_no FROM qc_refund WHERE order_id=?",String.class,id);jdbc.update("INSERT INTO qc_refund_event(order_id,status,actor_id,actor_name,note) VALUES(?,?,?,?,?)",id,status,actor,name,number+"："+note);if(notices!=null)notices.orderEvent(id,"REFUND_"+status);}
    private static String text(Map<String,Object> row,String key){return String.valueOf(row.get(key));}
    private static QicaiApiException error(HttpStatus status,String message){return new QicaiApiException(status,message);}
}

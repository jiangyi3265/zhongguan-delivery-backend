package com.ruoyi.qicai;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable in-app events share the business transaction; each reader owns their read state. */
@Service
public class QicaiNoticeService {
    private final JdbcTemplate jdbc;
    private static final Map<String,String> TITLES=new HashMap<>();
    static {
        TITLES.put("CREATED","订单已提交");TITLES.put("PAID","订单支付成功");TITLES.put("CANCELLED","订单已取消");
        TITLES.put("ASSIGNED","配送任务已分配");TITLES.put("PICKED_UP","商品已取货");TITLES.put("IN_DELIVERY","订单正在配送");
        TITLES.put("DELIVERED","订单已签收");TITLES.put("EXCEPTION","配送出现异常");TITLES.put("RESUMED","配送已恢复");
        TITLES.put("REASSIGNED","配送任务已转交");TITLES.put("REFUND_REQUESTED","退款申请已提交");
        TITLES.put("REFUND_APPROVED","退款审核已通过");TITLES.put("REFUND_REJECTED","退款申请未通过");
        TITLES.put("REFUND_PROCESSING","退款正在处理");TITLES.put("REFUND_SUCCESS","订单退款成功");
        TITLES.put("REFUND_ABNORMAL","退款需要核查");TITLES.put("REFUND_CLOSED","退款已关闭");
    }
    public QicaiNoticeService(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public void orderEvent(long orderId,String type) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Order notices require a business transaction");
        if(!TITLES.containsKey(type))throw new IllegalArgumentException("Unknown order event");
        Map<String,Object> order=jdbc.queryForMap("SELECT o.id,o.order_no,o.customer_id,o.version,d.id AS task_id,d.version AS task_version,d.courier_id FROM qc_order o LEFT JOIN qc_delivery_task d ON d.order_id=o.id WHERE o.id=?",orderId);
        String key=type+":"+order.get("version")+":"+order.get("task_version");
        String content="订单 "+order.get("order_no")+"："+TITLES.get(type)+"。请进入详情查看最新进度。";
        write(orderId,order.get("task_id"),key,type,"customer",((Number)order.get("customer_id")).longValue(),content);
        write(orderId,order.get("task_id"),key,type,"admin",0,content);
        if(order.get("courier_id")!=null)write(orderId,order.get("task_id"),key,type,"courier",((Number)order.get("courier_id")).longValue(),content);
    }
    public void transferred(long orderId,long oldCourier,long version) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Order notices require a business transaction");
        write(orderId,null,"REASSIGNED:"+version,"REASSIGNED","courier",oldCourier,"原配送任务已转交给其他配送员，无需继续配送。");
    }
    private void write(long orderId,Object taskId,String key,String event,String type,long recipient,String content) {
        jdbc.update("INSERT IGNORE INTO qc_order_notice(order_id,task_id,event_key,event_type,recipient_type,recipient_id,title,content) VALUES(?,?,?,?,?,?,?,?)",orderId,taskId,key,event,type,recipient,TITLES.get(event),content);
    }
    private String visible(String type,long id) {
        if(!Arrays.asList("customer","courier","admin").contains(type)||id<1)throw new QicaiApiException(HttpStatus.UNAUTHORIZED,"消息身份无效");
        return " FROM qc_order_notice n LEFT JOIN qc_notice_read r ON r.notice_id=n.id AND r.principal_type=? AND r.principal_id=? WHERE n.recipient_type=? AND n.recipient_id=? AND NOT EXISTS(SELECT 1 FROM qc_notice_hidden h WHERE h.notice_id=n.id AND h.principal_type=? AND h.principal_id=?)";
    }
    private List<Object> args(String type,long id) {return new ArrayList<>(Arrays.asList(type,id,type,"admin".equals(type)?0L:id,type,id));}
    @Transactional
    public void hide(String type,long id,long notice) {
        visible(type,id);
        // Check original ownership so repeated deletion is idempotent. Never
        // delete the shared event or another recipient's state.
        if(jdbc.queryForObject("SELECT COUNT(*) FROM qc_order_notice WHERE id=? AND recipient_type=? AND recipient_id=?",Long.class,notice,type,"admin".equals(type)?0L:id)!=1)
            throw new QicaiApiException(HttpStatus.NOT_FOUND,"消息不存在");
        jdbc.update("INSERT IGNORE INTO qc_notice_hidden(notice_id,principal_type,principal_id) VALUES(?,?,?)",notice,type,id);
    }
    public Map<String,Object> summary(String type,long id) {
        String from=visible(type,id);List<Object> values=args(type,id);
        return jdbc.queryForMap("SELECT COUNT(CASE WHEN r.notice_id IS NULL THEN 1 END) AS unread,COALESCE(MAX(n.id),0) AS latestId,COALESCE(MAX(CASE WHEN r.notice_id IS NULL THEN n.id END),0) AS latestUnreadId"+from,values.toArray());
    }
    public Map<String,Object> list(String type,long id,boolean unread,int pageNum,int pageSize) {
        return list(type,id,unread,pageNum,pageSize,null);
    }
    public Map<String,Object> list(String type,long id,boolean unread,int pageNum,int pageSize,Long beforeId) {
        if(pageNum<1 || pageNum>100000 || pageSize<1 || pageSize>100)throw new QicaiApiException(HttpStatus.BAD_REQUEST,"分页参数无效");
        if(beforeId!=null && (beforeId<1 || pageNum!=1))throw new QicaiApiException(HttpStatus.BAD_REQUEST,"消息游标无效，请刷新列表");
        String from=visible(type,id)+(unread?" AND r.notice_id IS NULL":"");List<Object> values=args(type,id);
        if(beforeId!=null) {from+=" AND n.id<?";values.add(beforeId);}
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,values.toArray());
        // The extra row decides exhaustion from the actual page, even if a new
        // event arrives between COUNT and SELECT. Older cursors never shift.
        values.add(pageSize+1);values.add((long)(pageNum-1)*pageSize);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT n.id,n.order_id AS orderId,CASE WHEN n.recipient_type='courier' AND NOT EXISTS(SELECT 1 FROM qc_delivery_task d WHERE d.id=n.task_id AND d.courier_id=n.recipient_id) THEN NULL ELSE n.task_id END AS taskId,n.event_type AS eventType,n.title,n.content,n.created_at AS createdAt,r.read_at AS readAt"+from+" ORDER BY n.id DESC LIMIT ? OFFSET ?",values.toArray());
        boolean more=rows.size()>pageSize;if(more)rows=new ArrayList<>(rows.subList(0,pageSize));
        Map<String,Object> result=new LinkedHashMap<>();result.put("rows",rows);result.put("total",total);result.put("pageNum",pageNum);result.put("pageSize",pageSize);result.put("hasMore",more);return result;
    }
    @Transactional
    public void read(String type,long id,long notice) {
        String from=visible(type,id);List<Object> values=args(type,id);values.add(notice);
        if(jdbc.queryForObject("SELECT COUNT(*)"+from+" AND n.id=?",Long.class,values.toArray())!=1)throw new QicaiApiException(HttpStatus.NOT_FOUND,"消息不存在");
        jdbc.update("INSERT IGNORE INTO qc_notice_read(notice_id,principal_type,principal_id) VALUES(?,?,?)",notice,type,id);
    }
    @Transactional
    public void readAll(String type,long id,long through) {
        visible(type,id);if(through<1)throw new QicaiApiException(HttpStatus.BAD_REQUEST,"请先读取消息列表");
        jdbc.update("INSERT IGNORE INTO qc_notice_read(notice_id,principal_type,principal_id) SELECT id,?,? FROM qc_order_notice WHERE recipient_type=? AND recipient_id=? AND id<=?",type,id,type,"admin".equals(type)?0L:id,through);
    }
}

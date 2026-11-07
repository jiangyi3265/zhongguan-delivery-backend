package com.ruoyi.qicai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.qicai.payment.WechatPayProperties;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Immutable reconciliation snapshots. Importing a bill never changes a payment or refund. */
@Service
public class QicaiReconciliationService {
    private final JdbcTemplate jdbc; private final WechatPayProperties config; private final ObjectMapper json=new ObjectMapper();
    public QicaiReconciliationService(JdbcTemplate jdbc,WechatPayProperties config) {this.jdbc=jdbc;this.config=config;}
    private static QicaiApiException bad(String message) { return new QicaiApiException(HttpStatus.BAD_REQUEST,message); }

    @Transactional(isolation=Isolation.REPEATABLE_READ,rollbackFor=Exception.class)
    public Map<String,Object> upload(MultipartFile file,String billDate) throws Exception {
        LocalDate date=date(billDate);
        if(config.getMerchantId().isEmpty() || config.getAppId().isEmpty()) throw new QicaiApiException(HttpStatus.SERVICE_UNAVAILABLE,"请先配置微信支付商户号和AppID");
        if(file.getSize()>5*1024*1024)throw bad("账单不能超过5MB");
        byte[] bytes=file.getBytes();
        List<Map<String,Object>> rows=QicaiTradeBill.parse(bytes,date,config.getMerchantId(),config.getAppId());
        String hash=QicaiAuthService.sha256(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO qc_recon_bill(bill_date,source_hash,merchant_id,normalized_rows,imported_by) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)",billDate,hash,config.getMerchantId(),json.writeValueAsString(rows),SecurityUtils.getUserId());
        long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        List<Long> existing=jdbc.queryForList("SELECT id FROM qc_recon_run WHERE bill_id=? ORDER BY id DESC LIMIT 1",Long.class,id);
        return existing.isEmpty()?reconcile(id,rows,billDate):detail(existing.get(0));
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ,rollbackFor=Exception.class)
    public Map<String,Object> rerun(long billId) throws Exception {
        List<Map<String,Object>> bills=jdbc.queryForList("SELECT bill_date,normalized_rows,merchant_id FROM qc_recon_bill WHERE id=?",billId);
        if(bills.isEmpty())throw new QicaiApiException(HttpStatus.NOT_FOUND,"账单不存在");
        if(!config.getMerchantId().equals(bills.get(0).get("merchant_id")))throw bad("账单所属商户与当前配置不一致，无法复查");
        return reconcile(billId,json.readValue(bills.get(0).get("normalized_rows").toString(),new TypeReference<List<Map<String,Object>>>(){}),bills.get(0).get("bill_date").toString());
    }

    private Map<String,Object> reconcile(long billId,List<Map<String,Object>> rows,String date) {
        // A consistent DB snapshot records the local state at this run. A later rerun is a new record.
        List<Map<String,Object>> local=jdbc.queryForList("SELECT p.*,o.payment_status AS order_payment_status,o.payable_amount FROM qc_payment p JOIN qc_order o ON o.id=p.order_id WHERE p.merchant_id=? LIMIT 100001",config.getMerchantId());
        List<Map<String,Object>> refunds=jdbc.queryForList("SELECT r.* FROM qc_refund r JOIN qc_payment p ON p.order_id=r.order_id WHERE p.merchant_id=? LIMIT 100001",config.getMerchantId());
        if(local.size()>100000 || refunds.size()>100000)throw bad("本商户记录超过单次对账上限，请按商户分库归档后重试");
        Map<String,Map<String,Object>> payments=new HashMap<>(),refundMap=new HashMap<>();
        for(Map<String,Object> p:local)payments.put(p.get("out_trade_no").toString(),p);
        for(Map<String,Object> r:refunds)refundMap.put(r.get("out_refund_no").toString(),r);
        Set<String> paidSeen=new HashSet<>(),refundSeen=new HashSet<>();List<Map<String,Object>> items=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            boolean payment="SUCCESS".equals(row.get("kind"));
            String trade=row.get("trade").toString(),refund=row.get("refund").toString();
            Map<String,Object> p=payments.get(trade),r=payment?null:refundMap.get(refund);
            if(payment)paidSeen.add(trade);else refundSeen.add(refund);
            BigDecimal bill=new BigDecimal(row.get(payment?"order":"refundAmount").toString());
            Map<String,Object> item=item(((Number)row.get("line")).intValue(),payment?"PAYMENT":"REFUND",trade,refund,p==null?null:p.get("order_id"),bill,new BigDecimal(row.get("fee").toString()));
            List<String> issues=new ArrayList<>();
            if(p==null) issues.add("渠道有交易，本地无支付记录");
            else {
                if(!config.getAppId().equals(p.get("app_id")) || !"CNY".equals(p.get("currency")))issues.add("本地AppID或币种不一致");
                if(!row.get("transaction").equals(p.get("transaction_id")))issues.add("微信交易单号不一致");
                if(!"PAID".equals(p.get("status")))issues.add("渠道已支付，本地支付状态未确认");
                BigDecimal amount=BigDecimal.valueOf(((Number)p.get("amount_fen")).longValue(),2);
                if(payment) {
                    item.put("local",amount);
                    if(amount.compareTo(bill)!=0 || amount.compareTo((BigDecimal)p.get("payable_amount"))!=0)issues.add("订单或支付金额不一致");
                    if(!day(p.get("paid_at"),date))issues.add("本地支付日期与账单不同");
                    if(!Arrays.asList("PAID","REFUND_REVIEW","REFUNDING","REFUNDED").contains(p.get("order_payment_status")))issues.add("业务订单支付状态不一致");
                } else if(r==null)issues.add("渠道退款未匹配当前本地记录，请核对历史退款申请");
                else {
                    BigDecimal amountRefund=BigDecimal.valueOf(((Number)r.get("amount_fen")).longValue(),2);item.put("local",amountRefund);
                    if(!Objects.equals(r.get("order_id"),p.get("order_id")))issues.add("退款所属订单不一致");
                    if(amountRefund.compareTo(bill)!=0)issues.add("退款金额不一致");
                    if(!row.get("refundId").equals(r.get("refund_id")))issues.add("微信退款单号不一致");
                    String expected=String.valueOf(row.get("refundStatus")),actual=String.valueOf(r.get("status"));
                    if("SUCCESS".equals(actual) && !"REFUNDED".equals(p.get("order_payment_status")))issues.add("本地退款成功但业务订单未标记已退款");
                    if(Arrays.asList("APPROVED","PROCESSING","ABNORMAL").contains(actual) && !"REFUNDING".equals(p.get("order_payment_status")))issues.add("本地退款处理中但业务订单状态不一致");
                    if("SUCCESS".equals(expected) && !"SUCCESS".equals(actual))issues.add("渠道账单退款成功，本地状态未确认");
                    else if(!expected.equals(actual)) { item.put("outcome","REVIEW");item.put("message","账单是出账时的退款快照（"+expected+"），本地当前状态为"+actual+"，请查退款单核对"); }
                }
            }
            if(!issues.isEmpty()) {item.put("outcome","DIFFERENCE");item.put("message",String.join("；",issues));}
            items.add(item);
        }
        for(Map<String,Object> p:local) if("PAID".equals(p.get("status")) && day(p.get("paid_at"),date) && !paidSeen.contains(p.get("out_trade_no").toString())) {
            Map<String,Object> item=item(0,"PAYMENT",p.get("out_trade_no").toString(),"",p.get("order_id"),null,null);item.put("local",BigDecimal.valueOf(((Number)p.get("amount_fen")).longValue(),2));item.put("outcome","DIFFERENCE");item.put("message","本地当日已支付，渠道账单缺失");items.add(item);
        }
        for(Map<String,Object> r:refunds) if(!Arrays.asList("REQUESTED","REJECTED").contains(r.get("status")) && (day(r.get("reviewed_at"),date)||day(r.get("success_at"),date)) && !refundSeen.contains(r.get("out_refund_no").toString())) {
            Map<String,Object> p=local.stream().filter(v->Objects.equals(v.get("order_id"),r.get("order_id"))).findFirst().orElse(null);
            Map<String,Object> item=item(0,"REFUND",p==null?"":p.get("out_trade_no").toString(),r.get("out_refund_no").toString(),r.get("order_id"),null,null);item.put("local",BigDecimal.valueOf(((Number)r.get("amount_fen")).longValue(),2));item.put("outcome","REVIEW");item.put("message","本地当日审核或完成退款，本账单未出现；退款按渠道发起日出账，请核对原出账日期");items.add(item);
        }
        long differences=items.stream().filter(i->!"MATCH".equals(i.get("outcome"))).count();
        BigDecimal order=QicaiTradeBill.total(rows,"order"),refund=QicaiTradeBill.total(rows,"refundAmount"),settled=QicaiTradeBill.total(rows,"settled"),settledRefund=QicaiTradeBill.total(rows,"settledRefund"),fee=QicaiTradeBill.total(rows,"fee");
        BigDecimal net=settled.subtract(settledRefund).subtract(fee);if(net.precision()>18)throw bad("账单净额超过系统处理上限，请联系管理员");
        jdbc.update("INSERT INTO qc_recon_run(bill_id,bill_date,row_count,difference_count,order_amount,refund_amount,settled_amount,settled_refund,fee_amount,net_amount,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)",billId,date,items.size(),differences,order,refund,settled,settledRefund,fee,net,SecurityUtils.getUserId());
        long run=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        for(Map<String,Object> item:items)jdbc.update("INSERT INTO qc_recon_item(run_id,source_line,kind,trade_no,refund_no,order_id,outcome,message,local_amount,bill_amount,fee_amount) VALUES(?,?,?,?,?,?,?,?,?,?,?)",run,item.get("line"),item.get("kind"),item.get("trade"),item.get("refund"),item.get("orderId"),item.get("outcome"),item.get("message"),item.get("local"),item.get("bill"),item.get("fee"));
        return detail(run);
    }
    private static Map<String,Object> item(int line,String kind,String trade,String refund,Object id,BigDecimal bill,BigDecimal fee) {
        Map<String,Object> r=new LinkedHashMap<>();r.put("line",line);r.put("kind",kind);r.put("trade",trade);r.put("refund",refund);r.put("orderId",id);r.put("bill",bill);r.put("fee",fee);r.put("outcome","MATCH");r.put("message","金额与渠道标识一致");return r;
    }
    private static boolean day(Object timestamp,String date) { return timestamp!=null && timestamp.toString().startsWith(date); }
    private Map<String,Object> moneyStrings(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        row.forEach((key,value)->result.put(key,value instanceof BigDecimal?((BigDecimal)value).toPlainString():value));
        return result;
    }
    private static LocalDate date(String raw) { try { LocalDate value=LocalDate.parse(raw);if(!value.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))))throw bad("请选择北京时间昨日或更早的账单日期");return value; }catch(DateTimeException|NullPointerException ex){throw bad("账单日期格式应为YYYY-MM-DD");} }
    public Map<String,Object> runs(int pageNum,int pageSize) { return page("SELECT id,bill_id AS billId,bill_date AS billDate,row_count AS rowCount,difference_count AS differenceCount,order_amount AS orderAmount,refund_amount AS refundAmount,fee_amount AS feeAmount,net_amount AS netAmount,created_at AS createdAt FROM qc_recon_run ORDER BY id DESC","SELECT COUNT(*) FROM qc_recon_run",Collections.emptyList(),pageNum,pageSize); }
    public Map<String,Object> detail(long id) { List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,bill_id AS billId,bill_date AS billDate,row_count AS rowCount,difference_count AS differenceCount,order_amount AS orderAmount,refund_amount AS refundAmount,settled_amount AS settledAmount,settled_refund AS settledRefund,fee_amount AS feeAmount,net_amount AS netAmount,created_at AS createdAt FROM qc_recon_run WHERE id=?",id);if(rows.isEmpty())throw new QicaiApiException(HttpStatus.NOT_FOUND,"对账记录不存在");return moneyStrings(rows.get(0)); }
    public Map<String,Object> items(long run,String outcome,int pageNum,int pageSize) {
        detail(run);List<Object> args=new ArrayList<>();args.add(run);String filter=" WHERE run_id=?";
        if(outcome!=null && !outcome.isEmpty()) { if(!Arrays.asList("MATCH","DIFFERENCE","REVIEW").contains(outcome))throw bad("对账结果无效");filter+=" AND outcome=?";args.add(outcome); }
        return page("SELECT id,source_line AS sourceLine,kind,trade_no AS tradeNo,refund_no AS refundNo,order_id AS orderId,outcome,message,local_amount AS localAmount,bill_amount AS billAmount,fee_amount AS feeAmount FROM qc_recon_item"+filter+" ORDER BY id","SELECT COUNT(*) FROM qc_recon_item"+filter,args,pageNum,pageSize);
    }
    private Map<String,Object> page(String select,String count,List<Object> args,int pageNum,int pageSize) {
        int p=Math.max(1,Math.min(pageNum,100000)),s=Math.max(1,Math.min(pageSize,100));long total=jdbc.queryForObject(count,Long.class,args.toArray());List<Object> values=new ArrayList<>(args);values.add(s);values.add((long)(p-1)*s);
        Map<String,Object> result=new LinkedHashMap<>();result.put("rows",jdbc.queryForList(select+" LIMIT ? OFFSET ?",values.toArray()).stream().map(this::moneyStrings).collect(java.util.stream.Collectors.toList()));result.put("total",total);result.put("pageNum",p);result.put("pageSize",s);return result;
    }
    public byte[] export(long run) {
        detail(run);StringBuilder csv=new StringBuilder("\ufeff类型,商户订单号,商户退款单号,本地订单ID,结果,说明,本地金额,账单金额,手续费\r\n");
        for(Map<String,Object> row:jdbc.queryForList("SELECT kind,trade_no,refund_no,order_id,outcome,message,local_amount,bill_amount,fee_amount FROM qc_recon_item WHERE run_id=? ORDER BY id",run)) {
            List<String> cells=new ArrayList<>();for(String key:Arrays.asList("kind","trade_no","refund_no","order_id","outcome","message","local_amount","bill_amount","fee_amount")) {String v=row.get(key)==null?"":row.get(key).toString();if((key.equals("trade_no")||key.equals("refund_no")) && v.matches("[0-9]{15,}") || v.matches("^[=+\\-@\\t\\r].*") && !v.matches("-?[0-9]+(\\.[0-9]+)?"))v="'"+v;cells.add("\""+v.replace("\"","\"\"")+"\"");}csv.append(String.join(",",cells)).append("\r\n");
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}

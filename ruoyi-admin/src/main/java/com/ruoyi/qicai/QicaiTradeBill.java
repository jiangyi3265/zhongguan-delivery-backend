package com.ruoyi.qicai;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.springframework.http.HttpStatus;

/** WeChat ALL CSV (current official layout); fees come from the bill, never an assumed rate. */
public final class QicaiTradeBill {
    private QicaiTradeBill() {}
    private static QicaiApiException bad(String text) { return new QicaiApiException(HttpStatus.BAD_REQUEST,text); }
    private static final List<String> REQUIRED=Arrays.asList("交易时间","公众账号ID","商户号","微信订单号","商户订单号","交易状态","货币种类","应结订单金额","订单金额","申请退款金额","退款金额","微信退款单号","商户退款单号","退款状态","手续费");
    public static List<Map<String,Object>> parse(byte[] bytes, LocalDate date,String merchant,String appId) {
        if(bytes.length==0 || bytes.length>5*1024*1024) throw bad("请选择5MB以内的微信ALL交易账单CSV文件");
        String text;
        try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch(CharacterCodingException ex) { throw bad("账单必须使用UTF-8编码，请上传原始CSV"); }
        String[] lines=text.replace("\ufeff","").split("\\r?\\n",-1);
        if(lines.length<3 || lines.length>20005) throw bad("账单内容不完整或超过20000行，请按日拆分");
        List<String> header=csv(lines[0]);
        if(new HashSet<>(header).size()!=header.size() || !header.containsAll(REQUIRED)) throw bad("请上传包含订单金额、申请退款金额及手续费的ALL交易账单");
        List<Map<String,Object>> rows=new ArrayList<>();Set<String> seen=new HashSet<>();
        int summary=-1;
        for(int i=1;i<lines.length;i++) {
            if(lines[i].trim().isEmpty()) continue;
            List<String> cells=csv(lines[i]);
            if("总交易单数".equals(cells.get(0))) { summary=i;break; }
            if(cells.size()!=header.size()) throw bad("账单第"+(i+1)+"行列数不匹配");
            Map<String,String> raw=new HashMap<>();for(int n=0;n<header.size();n++)raw.put(header.get(n),cells.get(n));
            try {
                LocalDateTime time=LocalDateTime.parse(raw.get("交易时间"),DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT));
                if(!time.toLocalDate().equals(date)) throw bad("账单包含非所选日期的交易");
            } catch(DateTimeParseException ex) { throw bad("账单第"+(i+1)+"行交易时间无效"); }
            if(!merchant.equals(raw.get("商户号")) || !appId.equals(raw.get("公众账号ID"))) throw bad("账单商户号或AppID与本系统不一致");
            if(raw.containsKey("特约商户号") && !Arrays.asList("","0").contains(raw.get("特约商户号"))) throw bad("当前仅支持本商户直连账单");
            if(!"CNY".equals(raw.get("货币种类"))) throw bad("账单仅支持人民币CNY");
            String kind=raw.get("交易状态");
            if(!Arrays.asList("SUCCESS","REFUND").contains(kind)) throw bad("账单含不支持的交易状态："+kind);
            String trade=identifier(raw.get("商户订单号")),transaction=identifier(raw.get("微信订单号"));
            String refund="REFUND".equals(kind)?identifier(raw.get("商户退款单号")):"";
            String refundId="REFUND".equals(kind)?identifier(raw.get("微信退款单号")):"";
            if(!seen.add(kind+":"+(refund.isEmpty()?trade:refund))) throw bad("账单包含重复交易或退款单，请核对原始文件");
            BigDecimal order=money(raw.get("订单金额"),false),refundAmount=money(raw.get("申请退款金额"),false),settled=money(raw.get("应结订单金额"),false),settledRefund=money(raw.get("退款金额"),false),fee=money(raw.get("手续费"),true);
            if("SUCCESS".equals(kind) && (order.signum()<=0 || refundAmount.signum()!=0 || settledRefund.signum()!=0 || settled.compareTo(order)>0 || fee.signum()<0)
                || "REFUND".equals(kind) && (refundAmount.signum()<=0 || order.signum()!=0 || settled.signum()!=0 || settledRefund.compareTo(refundAmount)>0 || fee.signum()>0)) throw bad("账单第"+(i+1)+"行金额与交易类型不一致");
            String refundStatus=raw.get("退款状态");
            if("REFUND".equals(kind) && !Arrays.asList("SUCCESS","PROCESSING","FAIL","CHANGE").contains(refundStatus)) throw bad("账单退款状态无效");
            Map<String,Object> row=new LinkedHashMap<>();row.put("line",i+1);row.put("kind",kind);row.put("trade",trade);row.put("transaction",transaction);row.put("refund",refund);row.put("refundId",refundId);row.put("refundStatus",refundStatus);
            row.put("order",order.toPlainString());row.put("refundAmount",refundAmount.toPlainString());row.put("settled",settled.toPlainString());row.put("settledRefund",settledRefund.toPlainString());row.put("fee",fee.toPlainString());rows.add(row);
        }
        if(summary<0 || summary+1>=lines.length) throw bad("账单缺少汇总校验行，请上传完整原始CSV");
        List<String> sums=csv(lines[summary]),values=csv(lines[summary+1]);
        if(sums.size()!=values.size() || new HashSet<>(sums).size()!=sums.size()) throw bad("账单汇总行无效");
        Map<String,String> summaryValues=new HashMap<>();for(int i=0;i<sums.size();i++)summaryValues.put(sums.get(i),values.get(i));
        if(!String.valueOf(rows.size()).equals(summaryValues.get("总交易单数"))) throw bad("汇总交易单数与明细不一致");
        String[][] checks={{"订单总金额","order"},{"申请退款总金额","refundAmount"},{"应结订单总金额","settled"},{"退款总金额","settledRefund"},{"手续费总金额","fee"}};
        for(String[] check:checks) if(!summaryValues.containsKey(check[0]) || money(summaryValues.get(check[0]),"fee".equals(check[1])).compareTo(total(rows,check[1]))!=0) throw bad("账单汇总"+check[0]+"与明细不一致");
        for(int i=summary+2;i<lines.length;i++)if(!lines[i].trim().isEmpty())throw bad("账单汇总后包含多余内容");
        return rows;
    }
    public static BigDecimal total(List<Map<String,Object>> rows,String key) { BigDecimal result=new BigDecimal("0.00");for(Map<String,Object> row:rows)result=result.add(new BigDecimal(row.get(key).toString()));if(result.precision()>18)throw bad("账单金额超过系统处理上限，请联系管理员");return result; }
    static String identifier(String value) { if(value==null || !value.matches("[A-Za-z0-9_-]{1,64}") || "0".equals(value))throw bad("账单交易单号无效");return value; }
    static BigDecimal money(String value,boolean signed) { if(value==null || !value.matches((signed?"-?":"")+"[0-9]{1,12}\\.[0-9]{2}"))throw bad("账单金额必须为元，保留两位小数");return new BigDecimal(value); }
    static List<String> csv(String line) {
        // WeChat escapes custom-field commas before exporting. Also accept conventional quoted CSV.
        List<String> result=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++) { char c=line.charAt(i);if(c=='"' && (i==0 || line.charAt(i-1)!='\\')) { if(quoted && i+1<line.length() && line.charAt(i+1)=='"') {field.append('"');i++;}else if(field.length()==0 || quoted)quoted=!quoted;else field.append(c); }
            else if(c==',' && !quoted) {result.add(clean(field.toString()));field.setLength(0);}else field.append(c); }
        if(quoted)throw bad("账单CSV引号不完整");result.add(clean(field.toString()));return result;
    }
    private static String clean(String value) { String text=value.trim();return text.startsWith("`")?text.substring(1):text; }
}

package com.ruoyi.qicai;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QicaiTradeBillTest {
    private String csv() {return "交易时间,公众账号ID,商户号,微信订单号,商户订单号,交易状态,货币种类,应结订单金额,订单金额,申请退款金额,退款金额,微信退款单号,商户退款单号,退款状态,手续费\n"
        +"`2026-10-01 12:00:00,`wx123,`12345,`WX001,`QC001,`SUCCESS,`CNY,`1299.50,`1299.50,`0.00,`0.00,`0,`0,,`7.80\n"
        +"`2026-10-01 13:00:00,`wx123,`12345,`WX001,`QC001,`REFUND,`CNY,`0.00,`0.00,`1299.50,`1299.50,`WR001,`QR001,`PROCESSING,`-7.80\n"
        +"总交易单数,应结订单总金额,退款总金额,手续费总金额,订单总金额,申请退款总金额\n`2,`1299.50,`1299.50,`0.00,`1299.50,`1299.50\n";}
    private List<Map<String,Object>> parse(String text) {return QicaiTradeBill.parse(text.getBytes(StandardCharsets.UTF_8),LocalDate.parse("2026-10-01"),"12345","wx123");}
    @Test void readsPaymentAndRefundWithNegativeFeeAndSnapshotStatus() {List<Map<String,Object>> rows=parse(csv());assertEquals(2,rows.size());assertEquals("PROCESSING",rows.get(1).get("refundStatus"));assertEquals("-7.80",rows.get(1).get("fee"));assertEquals("0.00",QicaiTradeBill.total(rows,"fee").toPlainString());}
    @Test void rejectsTruncatedOrTamperedSummary() {assertThrows(QicaiApiException.class,()->parse(csv().substring(0,csv().indexOf("总交易单数"))));assertThrows(QicaiApiException.class,()->parse(csv().replace("`2,`1299.50","`3,`1299.50")));assertThrows(QicaiApiException.class,()->parse(csv().replace("`0.00,`1299.50,`1299.50\n","`1.00,`1299.50,`1299.50\n")));}
    @Test void rejectsWrongMerchantAppDateAndCurrency() {for(String[] change:new String[][]{{"wx123","wx999"},{"12345","67890"},{"2026-10-01","2026-10-02"},{"CNY","USD"}})assertThrows(QicaiApiException.class,()->parse(csv().replace(change[0],change[1])));}
    @Test void rejectsDuplicatePaymentAndRefundRows() {String text=csv();String payment=text.split("\n")[1];assertThrows(QicaiApiException.class,()->parse(text.replace("总交易单数",payment+"\n总交易单数")));}
    @Test void rejectsInvalidMoneyAndIdentifiers() {for(String value:Arrays.asList("1,299.50","1299.501","1e3","NaN","-1299.50"))assertThrows(QicaiApiException.class,()->parse(csv().replace("1299.50",value)));assertThrows(QicaiApiException.class,()->parse(csv().replace("QC001","=CMD()")));}
    @Test void rejectsUnsupportedLayoutsAndExtraContent() {assertThrows(QicaiApiException.class,()->parse(csv().replace("订单金额,申请退款金额","总金额,申请退款金额")));assertThrows(QicaiApiException.class,()->parse(csv()+"unexpected\n"));assertThrows(QicaiApiException.class,()->parse(csv().replace("SUCCESS,`CNY","REVOKED,`CNY")));}
}

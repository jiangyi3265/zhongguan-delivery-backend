package com.ruoyi.qicai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Business audit is part of the caller's transaction, with trusted actors and allowlisted values. */
final class QicaiBusinessAudit
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private QicaiBusinessAudit() { }

    static Map<String,Object> snapshot(JdbcTemplate jdbc, String type, long id)
    {
        String select;
        switch (type)
        {
            case "product": select="SELECT id,sku,name,category,unit,stock,business_price AS businessPrice,consumer_price AS consumerPrice,status,version,deleted,(SELECT image_hash FROM qc_product_media WHERE product_id=qc_product.id) AS imageHash,(SELECT brand_id FROM qc_product_brand WHERE product_id=qc_product.id) AS brandId FROM qc_product WHERE id=?"; break;
            case "brand": select="SELECT id,name,description,logo_url AS logoUrl,sort_order AS sortOrder,status,version FROM qc_brand WHERE id=?"; break;
            case "forwardWarehouse": select="SELECT id,name,address,latitude,longitude,opening_hours AS openingHours,sort_order AS sortOrder,status,version FROM qc_forward_warehouse WHERE id=?"; break;
            case "coupon": select="SELECT id,name,audience,threshold_amount AS thresholdAmount,discount_amount AS discountAmount,CAST(valid_from AS CHAR) AS validFrom,CAST(valid_to AS CHAR) AS validTo,status,version,deleted FROM qc_coupon WHERE id=?"; break;
            case "customer": select="SELECT id,audience,status,version,deleted FROM qc_customer WHERE id=?"; break;
            case "courier": select="SELECT id,status,available,version,deleted FROM qc_courier WHERE id=?"; break;
            case "order": select="SELECT id,status,payment_status AS paymentStatus,subtotal_amount AS subtotalAmount,discount_amount AS discountAmount,payable_amount AS payableAmount,version,deleted FROM qc_order WHERE id=?"; break;
            case "delivery": select="SELECT id,order_id AS orderId,courier_id AS courierId,status,exception_from AS exceptionFrom,version FROM qc_delivery_task WHERE id=?"; break;
            case "customerCoupon": select="SELECT id,customer_id AS customerId,coupon_id AS couponId,status,used_order_id AS usedOrderId FROM qc_customer_coupon WHERE id=?"; break;
            default: throw new IllegalArgumentException("Unsupported business audit object");
        }
        java.util.List<Map<String,Object>> rows=jdbc.queryForList(select+" FOR UPDATE",id);
        if(rows.isEmpty()) return null;
        Map<String,Object> result=new LinkedHashMap<String,Object>(rows.get(0));
        if("product".equals(type)) result.put("purchaseUnits",jdbc.queryForList("SELECT business_unit AS businessUnit,consumer_unit AS consumerUnit FROM qc_product_unit WHERE product_id=?",id));
        if("product".equals(type)) result.put("names",jdbc.queryForList("SELECT business_name AS businessName,consumer_name AS consumerName FROM qc_product_name WHERE product_id=?",id));
        if("customer".equals(type)) result.put("salesUserId",jdbc.queryForList("SELECT sales_user_id AS id FROM qc_customer_sales WHERE customer_id=?",id));
        if("brand".equals(type)) result.put("slides",jdbc.queryForList("SELECT position,image_url AS imageUrl,caption FROM qc_brand_slide WHERE brand_id=? ORDER BY position",id));
        return result;
    }

    static void admin(JdbcTemplate jdbc, String action, String type, long id, Map<String,Object> before)
    {
        LoginUser user=SecurityUtils.getLoginUser();
        if (user==null || user.getUserId()==null || user.getUsername()==null)
            throw new QicaiApiException(HttpStatus.UNAUTHORIZED,"管理员身份无效，请重新登录");
        Long eventId="delivery".equals(type)?jdbc.queryForObject("SELECT MAX(id) FROM qc_delivery_event WHERE delivery_id=?",Long.class,id):null;
        record(jdbc,"admin",user.getUserId(),user.getUsername(),action,type,id,before,snapshot(jdbc,type,id),eventId);
    }

    static void record(JdbcTemplate jdbc, String actorType, long actorId, String actorName,
            String action, String type, long id, Map<String,Object> before, Map<String,Object> after, Long eventId)
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Business audit requires the business transaction");
        if (actorName==null || actorName.length()>64) throw new IllegalStateException("Invalid audit actor");
        try
        {
            jdbc.update("INSERT INTO qc_business_audit(actor_type,actor_id,actor_name,action,object_type,object_id,before_values,after_values,related_event_id) VALUES(?,?,?,?,?,?,?,?,?)",
                    actorType,actorId,actorName,action,type,id,before==null?null:JSON.writeValueAsString(before),after==null?null:JSON.writeValueAsString(after),eventId);
        }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Business audit serialization failed"); }
    }
}

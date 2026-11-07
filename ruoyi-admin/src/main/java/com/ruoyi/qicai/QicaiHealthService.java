package com.ruoyi.qicai;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Deployment readiness probes real dependencies instead of reporting a constant UP. */
@Service
public class QicaiHealthService
{
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public QicaiHealthService(JdbcTemplate jdbc, StringRedisTemplate redis)
    {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    public Map<String, Object> readiness()
    {
        boolean databaseUp = false, redisUp = false, schemaUp = false;
        try
        {
            databaseUp = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
            schemaUp = databaseUp && schemaReady();
        }
        catch (RuntimeException ignored) { }
        try
        {
            redisUp = "PONG".equals(redis.execute((RedisCallback<String>) connection -> connection.ping()));
        }
        catch (RuntimeException ignored) { }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("service", "qicai-delivery");
        data.put("status", databaseUp && redisUp && schemaUp ? "UP" : "DOWN");
        data.put("database", databaseUp ? "UP" : "DOWN");
        data.put("schema", schemaUp ? "UP" : "DOWN");
        data.put("redis", redisUp ? "UP" : "DOWN");
        return data;
    }

    private boolean schemaReady()
    {
        java.util.Set<String> columns=new java.util.HashSet<String>();
        boolean exactStock=false, longNote=false, latitude=false, longitude=false;
        for (Map<String,Object> row:jdbc.queryForList("SELECT table_name AS tableName,column_name AS columnName,data_type AS dataType,character_maximum_length AS maxLength,numeric_scale AS numericScale FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name LIKE 'qc\\_%'"))
        {
            String name=row.get("tableName")+"."+row.get("columnName");columns.add(name);
            if ("qc_product.stock".equals(name)) exactStock="bigint".equals(row.get("dataType"));
            if ("qc_delivery_event.note".equals(name)) longNote=row.get("maxLength") instanceof Number && ((Number)row.get("maxLength")).longValue()>=512;
            if ("qc_order.delivery_latitude".equals(name)) latitude="decimal".equals(row.get("dataType")) && Integer.valueOf(7).equals(((Number)row.get("numericScale")).intValue());
            if ("qc_order.delivery_longitude".equals(name)) longitude="decimal".equals(row.get("dataType")) && Integer.valueOf(7).equals(((Number)row.get("numericScale")).intValue());
        }
        if (!exactStock || !longNote || !latitude || !longitude || !columns.containsAll(java.util.Arrays.asList(
                "qc_customer.auth_method","qc_customer.contact_phone","qc_customer.version","qc_courier.available","qc_session.expires_at",
                "qc_upload.file_name","qc_delivery_attachment.file_name","qc_delivery_task.exception_from","qc_delivery_event.actor_type","qc_delivery_event.actor_id","qc_delivery_event.actor_name",
                "qc_coupon.version","qc_customer_coupon.used_order_id","qc_order.payment_status","qc_order.idempotency_key","qc_order.delivery_location_name",
                "qc_payment.uncertain_attempts","qc_wechat_identity.open_id","qc_wechat_account.consumer_customer_id","qc_wechat_account.business_customer_id",
                "qc_refund.out_refund_no","qc_refund_event.actor_id","qc_payment_maintenance.next_attempt_at",
                "qc_business_audit.before_values","qc_business_audit.after_values","qc_business_audit.related_event_id","qc_schema_version.version",
                "qc_product_image.image_hash","qc_product_media.product_id","qc_order_item_media.order_item_id",
                "qc_recon_bill.source_hash","qc_recon_run.difference_count","qc_recon_item.outcome","qc_order_notice.event_key","qc_notice_read.read_at",
                "qc_product_unit.business_unit","qc_product_unit.consumer_unit","qc_brand.logo_url","qc_brand.version","qc_brand_slide.image_url","qc_product_brand.brand_id",
                "qc_forward_warehouse.version","qc_forward_warehouse.latitude","qc_forward_warehouse.longitude","qc_order_warehouse.order_id","qc_order_warehouse.warehouse_version","qc_notice_hidden.principal_id","qc_product_name.consumer_name","qc_sales_profile.invite_code","qc_customer_sales.sales_user_id"))) return false;
        Long indexes=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND ((table_name='qc_customer_coupon' AND index_name='idx_qc_customer_coupon_used_order' AND column_name='used_order_id' AND seq_in_index=1) OR (table_name='qc_order' AND index_name='uk_qc_order_idempotency' AND column_name='customer_id' AND seq_in_index=1 AND non_unique=0))",Long.class);
        Long constraints=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND enforced='YES' AND ((table_name='qc_product' AND constraint_name='ck_qc_product_stock_safe') OR (table_name='qc_order' AND constraint_name='ck_qc_order_location'))",Long.class);
        Long marker=jdbc.queryForObject("SELECT COUNT(*) FROM qc_schema_version WHERE version='commercial-remediation-20261017'",Long.class);
        Long extension=jdbc.queryForObject("SELECT COUNT(*) FROM qc_schema_version WHERE version='extensions-20261018'",Long.class);
        Long brand=jdbc.queryForObject("SELECT COUNT(*) FROM qc_schema_version WHERE version='brands-20261019'",Long.class);
        Long warehouse=jdbc.queryForObject("SELECT COUNT(*) FROM qc_schema_version WHERE version='forward-warehouse-20261101'",Long.class);
        return Long.valueOf(2).equals(indexes) && Long.valueOf(2).equals(constraints) && Long.valueOf(1).equals(marker) && Long.valueOf(1).equals(extension) && Long.valueOf(1).equals(brand) && Long.valueOf(1).equals(warehouse);
    }
}

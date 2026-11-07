package com.ruoyi.qicai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.qicai.QicaiAuthService.Principal;
import com.ruoyi.qicai.payment.WechatPaymentService;

@Service
public class QicaiService
{
    private static final DateTimeFormatter NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final List<String> PRODUCT_STATUS = Arrays.asList("ACTIVE", "INACTIVE");
    private static final List<String> CUSTOMER_STATUS = Arrays.asList("PENDING", "ACTIVE", "REJECTED", "DISABLED");
    private static final List<String> ORDER_STATUS = Arrays.asList("PENDING_PAYMENT", "PENDING_SHIPMENT", "ASSIGNED", "PICKED_UP", "IN_DELIVERY", "COMPLETED", "CANCELLED");
    private static final List<String> DELIVERY_STATUS = Arrays.asList("WAITING_ASSIGN", "ASSIGNED", "PICKED_UP", "IN_DELIVERY", "DELIVERED", "EXCEPTION");
    private static final List<String> COUPON_STATUS = Arrays.asList("ACTIVE", "INACTIVE");
    private static final List<String> COURIER_STATUS = Arrays.asList("ACTIVE", "DISABLED");


    private final JdbcTemplate jdbc;
    private final QicaiAuthService authService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final WechatPaymentService payments;

    public QicaiService(JdbcTemplate jdbc, QicaiAuthService authService, BCryptPasswordEncoder passwordEncoder, WechatPaymentService payments)
    {
        this.jdbc = jdbc;
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
        this.payments = payments;
    }

    private QicaiNoticeService notices;
    private QicaiWarehouseService warehouses;
    @org.springframework.beans.factory.annotation.Autowired
    public void setWarehouses(QicaiWarehouseService value) { warehouses=value; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setNotices(QicaiNoticeService value) { notices=value; }
    private QicaiProductMedia productMedia;
    @org.springframework.beans.factory.annotation.Autowired
    public void setProductMedia(QicaiProductMedia value) { productMedia=value; }
    private QicaiBrandService brands;
    @org.springframework.beans.factory.annotation.Autowired
    public void setBrands(QicaiBrandService value) { brands=value; }
    private Object adminMedia(Object result) { result=media(result);return brands==null?result:brands.attach(result); }
    private void brandFilter(StringBuilder sql,List<Object> args,Long brandId) {
        if(brandId==null) return;
        if(brandId<=0) throw new QicaiApiException(HttpStatus.BAD_REQUEST,"品牌编号无效");
        sql.append(" AND EXISTS (SELECT 1 FROM qc_product_brand pb JOIN qc_brand b ON b.id=pb.brand_id WHERE pb.product_id=qc_product.id AND b.status='ACTIVE' AND pb.brand_id=?)");args.add(brandId);
    }
    private Object media(Object result) { return productMedia==null?result:productMedia.attach(result); }
    @SuppressWarnings("unchecked")
    private void itemMedia(Map<String,Object> result) { if(productMedia!=null) productMedia.attachItems((List<Map<String,Object>>)result.get("items")); }

    /** Public storefront always exposes retail pricing, never an enterprise price. */
    public List<Map<String, Object>> catalogProducts(String keyword, String category)
    {
        return (List<Map<String,Object>>) catalogProducts(keyword, category, null, null);
    }

    public Object catalogProducts(String keyword, String category, Integer pageNum, Integer pageSize)
    {
        return catalogProducts(keyword,category,pageNum,pageSize,null);
    }

    public Object catalogProducts(String keyword, String category, Integer pageNum, Integer pageSize, Long brandId)
    {
        List<Object> args = new ArrayList<Object>();
        String select = "SELECT id,sku," + productNameSql(false) + " AS name,subtitle,category,COALESCE((SELECT u.consumer_unit FROM qc_product_unit u WHERE u.product_id=qc_product.id),unit) AS unit,consumer_price AS price,stock,'consumer' AS audience FROM qc_product";
        // Build the outer predicate independently: projection and brand subqueries both contain WHERE.
        StringBuilder filter = new StringBuilder(" WHERE deleted=0 AND status='ACTIVE'");
        if (keyword != null && !keyword.trim().isEmpty())
        {
            filter.append(" AND (" + productNameSql(false) + " LIKE ? OR sku LIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        if (category != null && !category.trim().isEmpty())
        {
            filter.append(" AND category=?");
            args.add(category.trim());
        }
        brandFilter(filter,args,brandId);
        return media(boundedPage(select + filter + " ORDER BY id", "SELECT COUNT(*) FROM qc_product" + filter, args, args, pageNum, pageSize));
    }

    /** Categories follow the current on-sale catalogue, independent of search and pagination. */
    public List<String> catalogCategories(Long brandId)
    {
        List<Object> args = new ArrayList<Object>();
        StringBuilder sql = new StringBuilder("SELECT DISTINCT category FROM qc_product WHERE deleted=0 AND status='ACTIVE' AND TRIM(category)<>''");
        brandFilter(sql, args, brandId);
        sql.append(" ORDER BY category");
        return jdbc.queryForList(sql.toString(), String.class, args.toArray());
    }

    public Map<String, Object> catalogProduct(long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,sku," + productNameSql(false) + " AS name,subtitle,category,COALESCE((SELECT u.consumer_unit FROM qc_product_unit u WHERE u.product_id=qc_product.id),unit) AS unit,consumer_price AS price,stock,'consumer' AS audience FROM qc_product WHERE id=? AND deleted=0 AND status='ACTIVE'", id);
        if (rows.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "商品不存在或已下架");
        }
        return (Map<String,Object>)media(rows.get(0));
    }

    public List<Map<String, Object>> mobileProducts(Principal principal, String keyword, String category)
    {
        return (List<Map<String,Object>>) mobileProducts(principal, keyword, category, null, null);
    }

    public Object mobileProducts(Principal principal, String keyword, String category, Integer pageNum, Integer pageSize)
    {
        return mobileProducts(principal,keyword,category,pageNum,pageSize,null);
    }

    public Object mobileProducts(Principal principal, String keyword, String category, Integer pageNum, Integer pageSize, Long brandId)
    {
        requireApprovedCustomer(principal);
        List<Object> args = new ArrayList<Object>();
        args.add(principal.getAudience());
        String select = "SELECT id,sku," + productNameSql("business".equals(principal.getAudience())) + " AS name,subtitle,category," + audienceUnitSql(principal) + " AS unit,stock,"
                + "CASE WHEN ?='business' THEN business_price ELSE consumer_price END AS price,"
                + "status,version FROM qc_product";
        StringBuilder filter = new StringBuilder(" WHERE deleted=0 AND status='ACTIVE'");
        if (keyword != null && !keyword.trim().isEmpty())
        {
            filter.append(" AND (" + productNameSql("business".equals(principal.getAudience())) + " LIKE ? OR sku LIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        if (category != null && !category.trim().isEmpty())
        {
            filter.append(" AND category=?");
            args.add(category.trim());
        }
        brandFilter(filter,args,brandId);
        Object result = boundedPage(select + filter + " ORDER BY id", "SELECT COUNT(*) FROM qc_product" + filter,
                args, new ArrayList<Object>(args.subList(1,args.size())), pageNum, pageSize);
        List<Map<String,Object>> products = result instanceof List ? (List<Map<String,Object>>) result
                : (List<Map<String,Object>>) ((Map<?,?>) result).get("rows");
        products.forEach(product -> product.put("audience", principal.getAudience()));
        return media(result);
    }

    public Map<String, Object> mobileProduct(Principal principal, long id)
    {
        requireApprovedCustomer(principal);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,sku," + productNameSql("business".equals(principal.getAudience())) + " AS name,subtitle,category," + audienceUnitSql(principal) + " AS unit,stock,CASE WHEN ?='business' THEN business_price ELSE consumer_price END AS price,status,version FROM qc_product WHERE id=? AND deleted=0 AND status='ACTIVE'",
                principal.getAudience(), id);
        if (rows.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "商品不存在或已下架");
        }
        Map<String, Object> product = rows.get(0);
        product.put("audience", principal.getAudience());
        return (Map<String,Object>)media(product);
    }

    @Transactional
    public Map<String, Object> createOrder(Principal principal, Map<String, Object> body)
    {
        // Serialize idempotency checks per customer, including truly concurrent retries.
        jdbc.queryForList("SELECT id FROM qc_customer WHERE id=? AND deleted=0 FOR UPDATE", principal.getId());
        Map<String, Object> customer = requireApprovedCustomer(principal);
        long productId = positiveLong(body, "productId");
        int quantity = QicaiAuthService.number(body, "quantity", 0);
        if (quantity < 1 || quantity > 9999)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "采购数量必须在1至9999之间");
        }
        String idempotencyKey = QicaiAuthService.required(body, "idempotencyKey", 64);
        // The customer row above already serializes this customer's submissions.
        // This transaction's first consistent read occurs after that lock is acquired,
        // so a waiting retry sees the prior committed order. A locking read for a
        // missing key would instead lock a shared index gap across customers and
        // deadlock with product locking when either transaction inserts its order.
        List<Map<String, Object>> duplicate = jdbc.queryForList(
                "SELECT id FROM qc_order WHERE customer_id=? AND idempotency_key=? LIMIT 1", principal.getId(), idempotencyKey);
        if (!duplicate.isEmpty())
        {
            return mobileOrder(principal, ((Number) duplicate.get(0).get("id")).longValue());
        }

        String contact = receiptValue(body, "contactName", customer.get("contactName"), 60);
        String phone = receiptValue(body, "contactPhone", customer.get("phone"), 20);
        Map<String,Object> warehouse = warehouses == null ? null : warehouses.select(principal,body);
        Map<String,Object> destination = new LinkedHashMap<>(body);
        if(warehouse!=null) {
            destination.put("deliveryAddress",warehouse.get("address"));
            destination.put("deliveryLatitude",warehouse.get("latitude"));
            destination.put("deliveryLongitude",warehouse.get("longitude"));
            destination.put("deliveryLocationName",warehouse.get("name"));
            destination.put("coordinateSystem","GCJ-02");
        }
        String address = receiptValue(destination, "deliveryAddress", customer.get("address"), 255);
        QicaiAuthService.validatePhone(phone);
        LocationInput location = location(destination, false);

        List<Map<String, Object>> products = jdbc.queryForList(
                "SELECT id,sku," + productNameSql("business".equals(principal.getAudience())) + " AS name," + audienceUnitSql(principal) + " AS unit,stock,business_price,consumer_price,status FROM qc_product WHERE id=? AND deleted=0 FOR UPDATE",
                productId);
        if (products.isEmpty() || !"ACTIVE".equals(String.valueOf(products.get(0).get("status"))))
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "商品不存在或已下架");
        }
        Map<String, Object> product = products.get(0);
        BigDecimal unitPrice = money(product.get("business".equals(principal.getAudience()) ? "business_price" : "consumer_price"));
        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        if (subtotal.compareTo(new BigDecimal("9999999999.99")) > 0)
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "订单金额超出上限，请减少数量");
        BigDecimal discount = BigDecimal.ZERO.setScale(2);
        Long customerCouponId = null;
        Object couponRaw = body.get("customerCouponId");
        if (couponRaw != null && !String.valueOf(couponRaw).trim().isEmpty())
        {
            long couponId = positiveLong(body, "customerCouponId");
            List<Map<String, Object>> coupons = jdbc.queryForList(
                    "SELECT cc.id,c.threshold_amount,c.discount_amount FROM qc_customer_coupon cc JOIN qc_coupon c ON c.id=cc.coupon_id WHERE cc.id=? AND cc.customer_id=? AND cc.status='AVAILABLE' AND c.status='ACTIVE' AND c.deleted=0 AND (c.audience='all' OR c.audience=?) AND NOW() BETWEEN c.valid_from AND c.valid_to FOR UPDATE",
                    couponId, principal.getId(), principal.getAudience());
            if (coupons.isEmpty())
            {
                throw new QicaiApiException(HttpStatus.CONFLICT, "优惠券不可用");
            }
            Map<String, Object> coupon = coupons.get(0);
            if (subtotal.compareTo(money(coupon.get("threshold_amount"))) < 0)
            {
                throw new QicaiApiException(HttpStatus.CONFLICT, "订单金额未达到优惠券使用门槛");
            }
            discount = money(coupon.get("discount_amount")).min(subtotal);
            customerCouponId = ((Number) coupon.get("id")).longValue();
        }

        // Client amounts are confirmation guards only. Pricing remains authoritative
        // on the server, under the product/coupon locks held by this transaction.
        BigDecimal payable = subtotal.subtract(discount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        if (body.containsKey("expectedUnitPrice") || body.containsKey("expectedPayableAmount"))
        {
            BigDecimal confirmedUnit = confirmedAmount(body.get("expectedUnitPrice"));
            BigDecimal confirmedTotal = confirmedAmount(body.get("expectedPayableAmount"));
            if (confirmedUnit.compareTo(unitPrice) != 0 || confirmedTotal.compareTo(payable) != 0)
                throw new QicaiApiException(HttpStatus.CONFLICT, "商品价格或优惠已变化，请核对最新金额后重新提交");
        }
        int stockChanged = jdbc.update("UPDATE qc_product SET stock=stock-?,version=version+1 WHERE id=? AND stock>=? AND deleted=0 AND status='ACTIVE'",
                quantity, productId, quantity);
        if (stockChanged == 0)
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "库存不足，请刷新后重试");
        }

        String remark = QicaiAuthService.nullable(body, "remark", 255);
        String orderNo = "QC" + LocalDateTime.now().format(NUMBER_TIME) + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String paymentStatus = "NOT_REQUIRED";
        if (payments.isEnabled()) paymentStatus = WechatPaymentService.amountFen(payable) == 0 ? "NO_PAYMENT_REQUIRED" : "UNPAID";
        String orderStatus = "UNPAID".equals(paymentStatus) ? "PENDING_PAYMENT" : "PENDING_SHIPMENT";
        jdbc.update("INSERT INTO qc_order(order_no,customer_id,audience,status,payment_status,subtotal_amount,discount_amount,payable_amount,contact_name,contact_phone,delivery_address,remark,customer_coupon_id,idempotency_key,delivery_latitude,delivery_longitude,delivery_location_name) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                orderNo, principal.getId(), principal.getAudience(), orderStatus, paymentStatus, subtotal, discount, payable,
                contact, phone, address, remark, customerCouponId, idempotencyKey, location.latitude, location.longitude, location.name);
        Long orderId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if(warehouses!=null) warehouses.snapshot(orderId,warehouse);
        jdbc.update("INSERT INTO qc_order_item(order_id,product_id,sku,product_name,unit,unit_price,quantity,line_amount) VALUES(?,?,?,?,?,?,?,?)",
                orderId, productId, product.get("sku"), product.get("name"), product.get("unit"), unitPrice, quantity, subtotal);
        if(productMedia!=null) productMedia.snapshot(orderId,productId);
        if (customerCouponId != null)
        {
            jdbc.update("UPDATE qc_customer_coupon SET status='USED',used_order_id=?,used_at=NOW() WHERE id=? AND status='AVAILABLE'",
                    orderId, customerCouponId);
        }
        if ("consumer".equals(principal.getAudience()) && "WECHAT".equals(customer.get("authMethod")))
        {
            // Receipt details are not login identifiers. Keep this in the order transaction,
            // after the idempotency check, so a retry never rewrites the latest address.
            jdbc.update("UPDATE qc_customer SET contact_name=?,contact_phone=?,address=?,version=version+1 WHERE id=? AND auth_method='WECHAT'",
                    contact, phone, warehouse==null ? address : customer.get("address"), principal.getId());
        }
        if(notices!=null) notices.orderEvent(orderId,"CREATED");
        return mobileOrder(principal, orderId.longValue());
    }

    private static BigDecimal confirmedAmount(Object raw)
    {
        try
        {
            String value = String.valueOf(raw);
            if (!value.matches("[0-9]{1,10}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
            return new BigDecimal(value);
        }
        catch (RuntimeException ex)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "请重新核对订单金额");
        }
    }

    @Transactional
    public Map<String, Object> submittedOrder(Principal principal, String key)
    {
        Map<String, Object> input = new java.util.HashMap<String, Object>(); input.put("key", key);
        key = QicaiAuthService.required(input, "key", 64);
        // Serialize with in-flight creation, and scope the lookup to the owner.
        jdbc.queryForList("SELECT id FROM qc_customer WHERE id=? AND deleted=0 FOR UPDATE", principal.getId());
        List<Map<String, Object>> found = jdbc.queryForList("SELECT id FROM qc_order WHERE customer_id=? AND idempotency_key=? LIMIT 1", principal.getId(), key);
        return found.isEmpty() ? null : mobileOrder(principal, ((Number) found.get(0).get("id")).longValue());
    }

    private static String receiptValue(Map<String, Object> body, String key, Object fallback, int max)
    {
        Object raw = body.containsKey(key) ? body.get(key) : fallback;
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty() || ((String) raw).length() > max)
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "请填写有效的收货信息：" + key);
        return ((String) raw).trim();
    }

    public Map<String, Object> mobileOrders(Principal principal, String status, int pageNum, int pageSize)
    {
        authService.customerProfile(principal.getId());
        pageNum = safePage(pageNum);
        pageSize = safeSize(pageSize);
        List<Object> args = new ArrayList<Object>();
        args.add(principal.getId());
        String filter = " WHERE o.customer_id=? AND o.deleted=0";
        if (status != null && !status.trim().isEmpty())
        {
            validateEnum(status, ORDER_STATUS, "订单状态");
            filter += " AND o.status=?";
            args.add(status);
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM qc_order o" + filter, Integer.class, args.toArray());
        args.add(pageSize);
        args.add(((long) pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.id,o.order_no AS orderNo,o.status,o.payment_status AS paymentStatus,UNIX_TIMESTAMP(DATE_ADD(o.created_at,INTERVAL 30 MINUTE))*1000 AS paymentExpiresAt,o.subtotal_amount AS subtotalAmount,o.discount_amount AS discountAmount,o.payable_amount AS payableAmount,o.contact_name AS contactName,o.contact_phone AS contactPhone,o.delivery_address AS deliveryAddress,o.delivery_latitude AS deliveryLatitude,o.delivery_longitude AS deliveryLongitude,o.delivery_location_name AS deliveryLocationName,'GCJ-02' AS coordinateSystem,o.created_at AS createdAt,i.product_id AS productId,i.product_name AS productName,i.sku,i.unit,i.unit_price AS unitPrice,i.quantity FROM qc_order o JOIN qc_order_item i ON i.order_id=o.id" + filter + " ORDER BY o.id DESC LIMIT ? OFFSET ?",
                args.toArray());
        if(productMedia!=null) productMedia.attachOrderRows(rows);
        return page(rows, total == null ? 0 : total.intValue(), pageNum, pageSize);
    }

    public Map<String, Object> mobileOrder(Principal principal, long orderId)
    {
        authService.customerProfile(principal.getId());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,order_no AS orderNo,status,payment_status AS paymentStatus,UNIX_TIMESTAMP(DATE_ADD(created_at,INTERVAL 30 MINUTE))*1000 AS paymentExpiresAt,subtotal_amount AS subtotalAmount,discount_amount AS discountAmount,payable_amount AS payableAmount,contact_name AS contactName,contact_phone AS contactPhone,delivery_address AS deliveryAddress,delivery_latitude AS deliveryLatitude,delivery_longitude AS deliveryLongitude,delivery_location_name AS deliveryLocationName,'GCJ-02' AS coordinateSystem,remark,version,created_at AS createdAt,updated_at AS updatedAt FROM qc_order WHERE id=? AND customer_id=? AND deleted=0",
                orderId, principal.getId());
        if (rows.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>(rows.get(0));
        if(warehouses!=null) warehouses.attach(result,orderId);
        result.put("items", jdbc.queryForList(
                "SELECT id,product_id AS productId,sku,product_name AS productName,unit,unit_price AS unitPrice,quantity,line_amount AS lineAmount FROM qc_order_item WHERE order_id=? ORDER BY id",
                orderId));
        itemMedia(result);
        List<Map<String, Object>> deliveries = jdbc.queryForList(
                "SELECT d.id,d.task_no AS taskNo,d.status,d.warehouse_name AS warehouseName,d.warehouse_address AS warehouseAddress,d.delivery_window AS deliveryWindow,d.distance_km AS distanceKm,d.pickup_proof_url AS pickupProofUrl,d.delivery_proof_url AS deliveryProofUrl,d.signed_name AS signedName,c.name AS courierName,c.phone AS courierPhone,c.vehicle_no AS vehicleNo FROM qc_delivery_task d LEFT JOIN qc_courier c ON c.id=d.courier_id WHERE d.order_id=?",
                orderId);
        if (!deliveries.isEmpty())
        {
            Map<String, Object> delivery = deliveries.get(0);
            addProofs(delivery);
            delivery.put("events", jdbc.queryForList(
                    "SELECT id,status,note,created_at AS createdAt FROM qc_delivery_event WHERE delivery_id=? ORDER BY id",
                    delivery.get("id")));
            result.put("delivery", delivery);
        }
        return result;
    }

    @Transactional
    public Map<String,Object> updateOrderLocation(Principal principal, long orderId, Map<String,Object> body)
    {
        jdbc.queryForList("SELECT id FROM qc_customer WHERE id=? AND deleted=0 FOR UPDATE",principal.getId());
        requireApprovedCustomer(principal);
        LocationInput location=location(body,true);
        int version=QicaiAuthService.number(body,"version",-1);
        if (version<0) throw new QicaiApiException(HttpStatus.BAD_REQUEST,"请刷新订单后补选收货位置");
        Map<String,Object> order=first(jdbc.queryForList(
                "SELECT status,payment_status,version,delivery_latitude,delivery_longitude,delivery_location_name FROM qc_order WHERE id=? AND customer_id=? AND deleted=0 FOR UPDATE",
                orderId,principal.getId()),"订单不存在");
        if (((Number)order.get("version")).intValue()!=version
                || !Arrays.asList("PENDING_PAYMENT","PENDING_SHIPMENT","ASSIGNED","PICKED_UP","IN_DELIVERY").contains(order.get("status"))
                || !Arrays.asList("NOT_REQUIRED","NO_PAYMENT_REQUIRED","UNPAID","PAID").contains(order.get("payment_status"))
                || order.get("delivery_latitude")!=null || order.get("delivery_longitude")!=null || order.get("delivery_location_name")!=null)
            throw new QicaiApiException(HttpStatus.CONFLICT,"订单状态已变化或已有收货位置，请刷新核对");
        jdbc.update("UPDATE qc_order SET delivery_latitude=?,delivery_longitude=?,delivery_location_name=?,version=version+1 WHERE id=? AND version=?",
                location.latitude,location.longitude,location.name,orderId,version);
        return mobileOrder(principal,orderId);
    }

    @Transactional
    public Map<String, Object> confirmReceipt(Principal principal, long orderId, Map<String, Object> body)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,status FROM qc_order WHERE id=? AND customer_id=? AND deleted=0 FOR UPDATE",
                orderId, principal.getId());
        if (rows.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        String status = String.valueOf(rows.get(0).get("status"));
        if ("COMPLETED".equals(status))
        {
            return mobileOrder(principal, orderId);
        }
        if (!"IN_DELIVERY".equals(status))
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "当前订单状态不能确认收货");
        }
        List<Map<String, Object>> deliveries = jdbc.queryForList("SELECT id,status FROM qc_delivery_task WHERE order_id=?", orderId);
        if (deliveries.size() != 1 || !"IN_DELIVERY".equals(deliveries.get(0).get("status")))
            throw new QicaiApiException(HttpStatus.CONFLICT, "配送任务尚未恢复正常配送，不能确认收货");
        String signedName = QicaiAuthService.required(body, "signedName", 60);
        List<String> proofs = authService.requireUploadedImages(principal, body, "proofUrls", "proofUrl");
        String proofUrl = proofs.get(0);
        jdbc.update("UPDATE qc_order SET status='COMPLETED',version=version+1 WHERE id=?", orderId);
        String receiptRemark = QicaiAuthService.nullable(body, "remark", 255);
        if (!deliveries.isEmpty())
        {
            long deliveryId = ((Number) deliveries.get(0).get("id")).longValue();
            jdbc.update("UPDATE qc_delivery_task SET status='DELIVERED',signed_name=?,delivery_proof_url=COALESCE(?,delivery_proof_url),version=version+1 WHERE id=?",
                    signedName, proofUrl, deliveryId);
            saveProofs(deliveryId, "DELIVERY", proofs);
            addDeliveryEvent(deliveryId, "DELIVERED", receiptRemark == null ? "客户确认收货" : "客户确认收货：" + receiptRemark, principal);
        }
        return mobileOrder(principal, orderId);
    }

    public List<Map<String, Object>> mobileCoupons(Principal principal)
    {
        authService.customerProfile(principal.getId());
        return jdbc.queryForList(
                "SELECT c.id AS couponId,c.name,c.audience,c.threshold_amount AS thresholdAmount,c.discount_amount AS discountAmount,c.valid_from AS validFrom,c.valid_to AS validTo,cc.id AS customerCouponId,CASE WHEN cc.status='AVAILABLE' AND NOW()>c.valid_to THEN 'EXPIRED' WHEN cc.status='AVAILABLE' AND (c.status<>'ACTIVE' OR NOW()<c.valid_from OR (c.audience<>'all' AND c.audience<>?)) THEN 'INVALID' ELSE COALESCE(cc.status,'UNCLAIMED') END AS claimStatus,(c.status='ACTIVE' AND (c.audience='all' OR c.audience=?) AND NOW() BETWEEN c.valid_from AND c.valid_to) AS usable FROM qc_coupon c LEFT JOIN qc_customer_coupon cc ON cc.coupon_id=c.id AND cc.customer_id=? WHERE c.deleted=0 AND (cc.id IS NOT NULL OR (c.status='ACTIVE' AND NOW() BETWEEN c.valid_from AND c.valid_to AND (c.audience='all' OR c.audience=?))) ORDER BY c.id",
                principal.getAudience(), principal.getAudience(), principal.getId(), principal.getAudience());
    }

    @Transactional
    public Map<String, Object> claimCoupon(Principal principal, long couponId)
    {
        authService.customerProfile(principal.getId());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qc_coupon WHERE id=? AND deleted=0 AND status='ACTIVE' AND NOW() BETWEEN valid_from AND valid_to AND (audience='all' OR audience=?)",
                Integer.class, couponId, principal.getAudience());
        if (count == null || count == 0)
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "优惠券不存在或不可领取");
        }
        int changed = jdbc.update("INSERT IGNORE INTO qc_customer_coupon(customer_id,coupon_id,status) VALUES(?,?,'AVAILABLE')",
                principal.getId(), couponId);
        if (changed == 0)
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "该优惠券已领取");
        }
        return first(jdbc.queryForList(
                "SELECT id AS customerCouponId,customer_id AS customerId,coupon_id AS couponId,status,claimed_at AS claimedAt FROM qc_customer_coupon WHERE customer_id=? AND coupon_id=?",
                principal.getId(), couponId), "优惠券记录不存在");
    }

    public List<Map<String, Object>> courierTasks(Principal principal, String status)
    {
        return (List<Map<String,Object>>) courierTasks(principal,status,null,null);
    }

    public Object courierTasks(Principal principal, String status, Integer pageNum, Integer pageSize)
    {
        List<Object> args = new ArrayList<Object>();
        args.add(principal.getId());
        String filter = " WHERE d.courier_id=?";
        if (status != null && !status.trim().isEmpty())
        {
            validateEnum(status, DELIVERY_STATUS, "配送状态");
            filter += " AND d.status=?";
            args.add(status);
        }
        Object result = boundedPage(deliverySelect() + filter + " ORDER BY d.id DESC",
                "SELECT COUNT(*) FROM qc_delivery_task d" + filter, args, args, pageNum, pageSize);
        if (result instanceof Map)
            ((Map<String,Object>) result).put("summary", jdbc.queryForMap(
                    "SELECT COUNT(*) AS total,COALESCE(SUM(status='ASSIGNED'),0) AS assigned,COALESCE(SUM(status='PICKED_UP'),0) AS pickedUp,COALESCE(SUM(status='IN_DELIVERY'),0) AS inDelivery,COALESCE(SUM(status='EXCEPTION'),0) AS exception,COALESCE(SUM(status='DELIVERED'),0) AS delivered FROM qc_delivery_task WHERE courier_id=?", principal.getId()));
        return result;
    }

    public Map<String, Object> courierTask(Principal principal, long taskId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(deliverySelect() + " WHERE d.id=? AND d.courier_id=?",
                taskId, principal.getId());
        if (rows.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "配送任务不存在");
        }
        Map<String, Object> task = new LinkedHashMap<String, Object>(rows.get(0));
        if(warehouses!=null) warehouses.attach(task,((Number)task.get("orderId")).longValue());
        addProofs(task);
        task.put("items", jdbc.queryForList(
                "SELECT id,product_id AS productId,sku,product_name AS productName,unit,quantity,line_amount AS lineAmount FROM qc_order_item WHERE order_id=?",
                task.get("orderId")));
        task.put("events", jdbc.queryForList(
                "SELECT id,status,note,created_at AS createdAt FROM qc_delivery_event WHERE delivery_id=? ORDER BY id",
                taskId));
        itemMedia(task);
        return task;
    }

    @Transactional
    public Map<String, Object> updateCourierTask(Principal principal, long taskId, Map<String, Object> body)
    {
        String requested = QicaiAuthService.required(body, "status", 24).toUpperCase();
        validateEnum(requested, DELIVERY_STATUS, "配送状态");
        // Each transition may only write its own evidence fields. Otherwise a later
        // transition could overwrite verified evidence without upload ownership checks.
        if ((!"PICKED_UP".equals(requested) && (body.containsKey("pickupProofUrl") || body.containsKey("pickupProofUrls")))
                || (!"DELIVERED".equals(requested) && (body.containsKey("deliveryProofUrl")
                        || body.containsKey("deliveryProofUrls") || body.containsKey("signedName"))))
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "当前配送操作不能修改其他阶段的凭证或签收信息");
        }
        // Match receipt/assignment lock order (order, then task), avoiding deadlocks.
        Map<String, Object> assigned = first(jdbc.queryForList("SELECT order_id FROM qc_delivery_task WHERE id=? AND courier_id=?", taskId, principal.getId()), "配送任务不存在");
        Map<String, Object> lockedOrder = first(jdbc.queryForList("SELECT id,status FROM qc_order WHERE id=? AND deleted=0 FOR UPDATE", assigned.get("order_id")), "订单不存在");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,order_id,status,version FROM qc_delivery_task WHERE id=? AND courier_id=? FOR UPDATE",
                taskId, principal.getId());
        if (rows.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "配送任务不存在");
        }
        Map<String, Object> row = rows.get(0);
        String current = String.valueOf(row.get("status"));
        if (requested.equals(current))
        {
            return courierTask(principal, taskId);
        }
        if (!validDeliveryTransition(current, requested))
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "配送状态不能从" + current + "变更为" + requested);
        }
        if (!current.equals(lockedOrder.get("status")))
            throw new QicaiApiException(HttpStatus.CONFLICT, "订单与任务状态不一致，请刷新后联系运营");
        String pickupProof = null;
        String deliveryProof = null;
        String signedName = null;
        List<String> proofs = Collections.emptyList();
        if ("PICKED_UP".equals(requested)) { proofs = authService.requireUploadedImages(principal, body, "pickupProofUrls", "pickupProofUrl"); pickupProof = proofs.get(0); }
        if ("DELIVERED".equals(requested))
        {
            proofs = authService.requireUploadedImages(principal, body, "deliveryProofUrls", "deliveryProofUrl");
            deliveryProof = proofs.get(0);
            signedName = QicaiAuthService.required(body, "signedName", 60);
        }
        jdbc.update("UPDATE qc_delivery_task SET status=?,pickup_proof_url=COALESCE(?,pickup_proof_url),delivery_proof_url=COALESCE(?,delivery_proof_url),signed_name=COALESCE(?,signed_name),version=version+1 WHERE id=?",
                requested, pickupProof, deliveryProof, signedName, taskId);
        if ("EXCEPTION".equals(requested))
            jdbc.update("UPDATE qc_delivery_task SET exception_from=? WHERE id=?", current, taskId);
        if (!proofs.isEmpty()) saveProofs(taskId, "PICKED_UP".equals(requested) ? "PICKUP" : "DELIVERY", proofs);
        long orderId = ((Number) row.get("order_id")).longValue();
        if ("PICKED_UP".equals(requested))
        {
            jdbc.update("UPDATE qc_order SET status='PICKED_UP',version=version+1 WHERE id=? AND status='ASSIGNED'", orderId);
        }
        else if ("IN_DELIVERY".equals(requested))
        {
            jdbc.update("UPDATE qc_order SET status='IN_DELIVERY',version=version+1 WHERE id=? AND status IN ('PICKED_UP','ASSIGNED')", orderId);
        }
        else if ("DELIVERED".equals(requested))
        {
            jdbc.update("UPDATE qc_order SET status='COMPLETED',version=version+1 WHERE id=? AND status<>'CANCELLED'", orderId);
        }
        addDeliveryEvent(taskId, requested, QicaiAuthService.nullable(body, "note", 255), principal);
        return courierTask(principal, taskId);
    }

    @Transactional
    public Map<String, Object> resumeCourierTask(Principal principal, long taskId, Map<String, Object> body)
    {
        if (body.containsKey("status") || body.containsKey("exceptionFrom"))
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "恢复阶段由服务器确认，不能自行指定");
        int version = QicaiAuthService.number(body, "version", -1);
        if (version < 0) throw new QicaiApiException(HttpStatus.BAD_REQUEST, "请刷新任务后重试");
        Map<String, Object> assigned = first(jdbc.queryForList("SELECT order_id FROM qc_delivery_task WHERE id=? AND courier_id=?", taskId, principal.getId()), "配送任务不存在");
        Map<String, Object> order = first(jdbc.queryForList("SELECT status FROM qc_order WHERE id=? AND deleted=0 FOR UPDATE", assigned.get("order_id")), "订单不存在");
        Map<String, Object> task = first(jdbc.queryForList("SELECT status,exception_from,version FROM qc_delivery_task WHERE id=? AND courier_id=? FOR UPDATE", taskId, principal.getId()), "配送任务不存在");
        String prior = String.valueOf(task.get("exception_from"));
        if (!"EXCEPTION".equals(task.get("status")) || ((Number) task.get("version")).intValue() != version
                || !Arrays.asList("ASSIGNED", "PICKED_UP", "IN_DELIVERY").contains(prior) || !prior.equals(order.get("status")))
            throw new QicaiApiException(HttpStatus.CONFLICT, "任务已变化或原阶段无法确认，请刷新后联系运营");
        jdbc.update("UPDATE qc_delivery_task SET status=?,exception_from=NULL,version=version+1 WHERE id=?", prior, taskId);
        addDeliveryEvent(taskId, prior, "配送员" + principal.getId() + "确认异常已解决，恢复原阶段", principal, true);
        return courierTask(principal, taskId);
    }

    public Map<String, Object> courierAvailability(Principal principal)
    {
        Map<String, Object> profile = new LinkedHashMap<String, Object>(first(jdbc.queryForList(
                "SELECT id,phone,name,vehicle_no AS vehicleNo,status,available,version FROM qc_courier WHERE id=? AND deleted=0",
                principal.getId()), "配送员不存在"));
        profile.put("available", QicaiAuthService.courierAvailable(profile.get("available")));
        return profile;
    }

    @Transactional
    public Map<String, Object> updateCourierAvailability(Principal principal, boolean available)
    {
        jdbc.update("UPDATE qc_courier SET available=?,version=version+1 WHERE id=? AND status='ACTIVE' AND deleted=0", available ? 1 : 0, principal.getId());
        return courierAvailability(principal);
    }

    public Map<String, Object> dashboard()
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("customers", scalar("SELECT COUNT(*) FROM qc_customer WHERE deleted=0"));
        result.put("pendingEnterprises", scalar("SELECT COUNT(*) FROM qc_customer WHERE audience='business' AND status='PENDING' AND deleted=0"));
        result.put("products", scalar("SELECT COUNT(*) FROM qc_product WHERE deleted=0"));
        result.put("orders", scalar("SELECT COUNT(*) FROM qc_order WHERE deleted=0"));
        result.put("pendingOrders", scalar("SELECT COUNT(*) FROM qc_order WHERE status='PENDING_SHIPMENT' AND deleted=0"));
        result.put("activeDeliveries", scalar("SELECT COUNT(*) FROM qc_delivery_task WHERE status IN ('ASSIGNED','PICKED_UP','IN_DELIVERY')"));
        BigDecimal revenue = jdbc.queryForObject("SELECT COALESCE(SUM(payable_amount),0) FROM qc_order WHERE status<>'CANCELLED' AND deleted=0", BigDecimal.class);
        result.put("revenue", revenue == null ? BigDecimal.ZERO : revenue);
        result.put("recentOrders", jdbc.queryForList(
                "SELECT o.id,o.order_no AS orderNo,o.status,o.payable_amount AS payableAmount,o.created_at AS createdAt,c.contact_name AS customerName,c.audience FROM qc_order o JOIN qc_customer c ON c.id=o.customer_id WHERE o.deleted=0 ORDER BY o.id DESC LIMIT 8"));
        result.put("statusSummary", jdbc.queryForList(
                "SELECT status,COUNT(*) AS count FROM qc_order WHERE deleted=0 GROUP BY status ORDER BY status"));
        return result;
    }

    public Map<String, Object> adminCustomers(String keyword, String audience, String status, int pageNum, int pageSize)
    {
        pageNum = safePage(pageNum);
        pageSize = safeSize(pageSize);
        List<Object> args = new ArrayList<Object>();
        String filter = " WHERE deleted=0";
        if (keyword != null && !keyword.trim().isEmpty())
        {
            filter += " AND (COALESCE(contact_phone,phone) LIKE ? OR contact_name LIKE ? OR company_name LIKE ? OR tax_id LIKE ?)";
            String like = "%" + keyword.trim() + "%";
            Collections.addAll(args, like, like, like, like);
        }
        if (audience != null && !audience.trim().isEmpty())
        {
            if (!"business".equals(audience) && !"consumer".equals(audience))
            {
                throw new QicaiApiException(HttpStatus.BAD_REQUEST, "客户类型不正确");
            }
            filter += " AND audience=?";
            args.add(audience);
        }
        if (status != null && !status.trim().isEmpty())
        {
            validateEnum(status, CUSTOMER_STATUS, "客户状态");
            filter += " AND status=?";
            args.add(status);
        }
        return pagedQuery("SELECT id,audience,COALESCE(contact_phone,phone) AS phone,auth_method AS authMethod,company_name AS companyName,contact_name AS contactName,tax_id AS taxId,address,status,version,(SELECT cs.sales_user_id FROM qc_customer_sales cs WHERE cs.customer_id=qc_customer.id) AS salesUserId,(SELECT u.nick_name FROM qc_customer_sales cs JOIN sys_user u ON u.user_id=cs.sales_user_id WHERE cs.customer_id=qc_customer.id) AS salesName,created_at AS createdAt,updated_at AS updatedAt FROM qc_customer" + filter + " ORDER BY id DESC",
                "SELECT COUNT(*) FROM qc_customer" + filter, args, pageNum, pageSize);
    }

    @Transactional
    public Map<String, Object> reviewCustomer(long id, Map<String, Object> body)
    {
        String status = QicaiAuthService.required(body, "status", 20).toUpperCase();
        if (!"ACTIVE".equals(status) && !"REJECTED".equals(status) && !"DISABLED".equals(status))
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "审核状态不正确");
        }
        int version = QicaiAuthService.number(body, "version", -1);
        if (version < 0)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "缺少客户资料版本号，请刷新后审核");
        }
        Map<String,Object> before=QicaiBusinessAudit.snapshot(jdbc,"customer",id);
        int changed = jdbc.update("UPDATE qc_customer SET status=?,version=version+1 WHERE id=? AND version=? AND deleted=0", status, id, version);
        if (changed == 0)
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "客户资料已更新或不存在，请刷新并重新审核");
        }
        if ("DISABLED".equals(status) || "REJECTED".equals(status))
            jdbc.update("DELETE FROM qc_session WHERE principal_type='customer' AND principal_id=?", id);
        QicaiBusinessAudit.admin(jdbc,"REVIEW_CUSTOMER","customer",id,before);
        return authService.customerProfile(id);
    }

    public Map<String, Object> adminProducts(String keyword, String status, int pageNum, int pageSize)
    {
        pageNum = safePage(pageNum);
        pageSize = safeSize(pageSize);
        List<Object> args = new ArrayList<Object>();
        String filter = " WHERE deleted=0";
        if (keyword != null && !keyword.trim().isEmpty())
        {
            filter += " AND (CONCAT_WS(' ',name," + productNameSql(true) + "," + productNameSql(false) + ") LIKE ? OR sku LIKE ?)";
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.trim().isEmpty())
        {
            validateEnum(status, PRODUCT_STATUS, "商品状态");
            filter += " AND status=?";
            args.add(status);
        }
        return (Map<String,Object>)adminMedia(pagedQuery("SELECT id,sku,name," + productNameSql(true) + " AS businessName," + productNameSql(false) + " AS consumerName,subtitle,category,unit,COALESCE((SELECT u.business_unit FROM qc_product_unit u WHERE u.product_id=qc_product.id),unit) AS businessUnit,COALESCE((SELECT u.consumer_unit FROM qc_product_unit u WHERE u.product_id=qc_product.id),unit) AS consumerUnit,stock,business_price AS businessPrice,consumer_price AS consumerPrice,status,version,created_at AS createdAt,updated_at AS updatedAt FROM qc_product" + filter + " ORDER BY id DESC",
                "SELECT COUNT(*) FROM qc_product" + filter, args, pageNum, pageSize));
    }

    @Transactional
    public Map<String, Object> createProduct(Map<String, Object> body)
    {
        ProductInput value = productInput(body);
        jdbc.update("INSERT INTO qc_product(sku,name,subtitle,category,unit,stock,business_price,consumer_price,status) VALUES(?,?,?,?,?,?,?,?,?)",
                value.sku, value.name, value.subtitle, value.category, value.unit, value.stock,
                value.businessPrice, value.consumerPrice, value.status);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        saveProductUnits(id.longValue(),body);
        saveProductNames(id.longValue(),body);
        if(productMedia!=null) productMedia.save(id.longValue(),body);
        if(brands!=null) brands.assign(id.longValue(),body);
        QicaiBusinessAudit.admin(jdbc,"CREATE_PRODUCT","product",id.longValue(),null);
        return adminProduct(id.longValue());
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> updateProduct(long id, Map<String, Object> body)
    {
        ProductInput value = productInput(body);
        int version = QicaiAuthService.number(body, "version", -1);
        if (version < 0)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "缺少商品版本号");
        }
        Map<String,Object> before = QicaiBusinessAudit.snapshot(jdbc,"product",id);
        QicaiInventory.requireRecoveryCapacity(jdbc,id,value.stock);
        int changed = jdbc.update("UPDATE qc_product SET sku=?,name=?,subtitle=?,category=?,unit=?,stock=?,business_price=?,consumer_price=?,status=?,version=version+1 WHERE id=? AND version=? AND deleted=0",
                value.sku, value.name, value.subtitle, value.category, value.unit, value.stock,
                value.businessPrice, value.consumerPrice, value.status, id, version);
        if (changed == 0)
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "商品已被修改或不存在，请刷新后重试");
        }
        saveProductUnits(id,body);
        saveProductNames(id,body);
        if(productMedia!=null) productMedia.save(id,body);
        if(brands!=null) brands.assign(id,body);
        QicaiBusinessAudit.admin(jdbc,"UPDATE_PRODUCT","product",id,before);
        return adminProduct(id);
    }

    @Transactional
    public void deleteProduct(long id)
    {
        Map<String,Object> before=QicaiBusinessAudit.snapshot(jdbc,"product",id);
        int changed = jdbc.update("UPDATE qc_product SET deleted=1,status='INACTIVE',version=version+1 WHERE id=? AND deleted=0", id);
        if (changed == 0)
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        QicaiBusinessAudit.admin(jdbc,"DELETE_PRODUCT","product",id,before);
    }

    public Map<String, Object> adminOrders(String keyword, String status, int pageNum, int pageSize) {
        return adminOrders(keyword,status,pageNum,pageSize,null,null);
    }
    public Map<String, Object> adminOrders(String keyword, String status, int pageNum, int pageSize,String audience,Long forwardWarehouseId)
    {
        pageNum = safePage(pageNum);
        pageSize = safeSize(pageSize);
        List<Object> args = new ArrayList<Object>();
        String filter = " WHERE o.deleted=0";
        if(audience!=null && !audience.isEmpty()) {
            if(!Arrays.asList("business","consumer").contains(audience))throw new QicaiApiException(HttpStatus.BAD_REQUEST,"客户类型无效");
            filter+=" AND o.audience=?";args.add(audience);
        }
        if(forwardWarehouseId!=null) {
            if(!"consumer".equals(audience) || forwardWarehouseId<0)throw new QicaiApiException(HttpStatus.BAD_REQUEST,"请在C端订单中选择前置仓");
            if(forwardWarehouseId==0)filter+=" AND NOT EXISTS(SELECT 1 FROM qc_order_warehouse ow WHERE ow.order_id=o.id)";
            else {filter+=" AND EXISTS(SELECT 1 FROM qc_order_warehouse ow WHERE ow.order_id=o.id AND ow.warehouse_id=?)";args.add(forwardWarehouseId);}
        }
        if (keyword != null && !keyword.trim().isEmpty())
        {
            filter += " AND (o.order_no LIKE ? OR o.contact_name LIKE ? OR o.contact_phone LIKE ?)";
            String like = "%" + keyword.trim() + "%";
            Collections.addAll(args, like, like, like);
        }
        if (status != null && !status.trim().isEmpty())
        {
            validateEnum(status, ORDER_STATUS, "订单状态");
            filter += " AND o.status=?";
            args.add(status);
        }
        return pagedQuery("SELECT o.id,o.order_no AS orderNo,o.customer_id AS customerId,o.audience,o.status,o.payment_status AS paymentStatus,UNIX_TIMESTAMP(DATE_ADD(o.created_at,INTERVAL 30 MINUTE))*1000 AS paymentExpiresAt,o.subtotal_amount AS subtotalAmount,o.discount_amount AS discountAmount,o.payable_amount AS payableAmount,o.contact_name AS contactName,o.contact_phone AS contactPhone,o.delivery_address AS deliveryAddress,o.delivery_latitude AS deliveryLatitude,o.delivery_longitude AS deliveryLongitude,o.delivery_location_name AS deliveryLocationName,'GCJ-02' AS coordinateSystem,o.remark,o.version,o.created_at AS createdAt,c.company_name AS companyName FROM qc_order o JOIN qc_customer c ON c.id=o.customer_id" + filter + " ORDER BY o.id DESC",
                "SELECT COUNT(*) FROM qc_order o" + filter, args, pageNum, pageSize);
    }

    public List<Map<String,Object>> orderWarehouses() {
        String count="COUNT(o.id) AS total,SUM(o.status='PENDING_PAYMENT') AS pendingPayment,SUM(o.status='PENDING_SHIPMENT') AS pendingShipment,SUM(o.status IN('ASSIGNED','PICKED_UP','IN_DELIVERY')) AS delivering,SUM(o.status='COMPLETED') AS completed";
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT w.id,w.name,w.address,w.status,"+count+" FROM qc_forward_warehouse w LEFT JOIN qc_order_warehouse ow ON ow.warehouse_id=w.id LEFT JOIN qc_order o ON o.id=ow.order_id AND o.audience='consumer' AND o.deleted=0 GROUP BY w.id,w.name,w.address,w.status,w.sort_order ORDER BY w.sort_order,w.id");
        Map<String,Object> unassigned=jdbc.queryForMap("SELECT "+count+" FROM qc_order o WHERE o.audience='consumer' AND o.deleted=0 AND NOT EXISTS(SELECT 1 FROM qc_order_warehouse ow WHERE ow.order_id=o.id)");
        unassigned.put("id",0L);unassigned.put("name","未关联前置仓");unassigned.put("address","历史订单及未选择仓点的订单");unassigned.put("status","LEGACY");rows.add(unassigned);return rows;
    }

    public Map<String, Object> adminOrder(long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.id,o.order_no AS orderNo,o.customer_id AS customerId,o.audience,o.status,o.payment_status AS paymentStatus,UNIX_TIMESTAMP(DATE_ADD(o.created_at,INTERVAL 30 MINUTE))*1000 AS paymentExpiresAt,o.subtotal_amount AS subtotalAmount,o.discount_amount AS discountAmount,o.payable_amount AS payableAmount,o.contact_name AS contactName,o.contact_phone AS contactPhone,o.delivery_address AS deliveryAddress,o.delivery_latitude AS deliveryLatitude,o.delivery_longitude AS deliveryLongitude,o.delivery_location_name AS deliveryLocationName,'GCJ-02' AS coordinateSystem,o.remark,o.version,o.created_at AS createdAt,c.company_name AS companyName FROM qc_order o JOIN qc_customer c ON c.id=o.customer_id WHERE o.id=? AND o.deleted=0",
                id);
        if (rows.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>(rows.get(0));
        if(warehouses!=null) warehouses.attach(result,id);
        result.put("items", jdbc.queryForList(
                "SELECT id,product_id AS productId,sku,product_name AS productName,unit,unit_price AS unitPrice,quantity,line_amount AS lineAmount FROM qc_order_item WHERE order_id=?",
                id));
        itemMedia(result);
        result.put("delivery", jdbc.queryForList(deliverySelect() + " WHERE d.order_id=?", id));
        return result;
    }

    @Transactional
    public Map<String, Object> cancelOrder(long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status,payment_status FROM qc_order WHERE id=? AND deleted=0 FOR UPDATE", id);
        if (rows.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        String status = String.valueOf(rows.get(0).get("status"));
        if ("CANCELLED".equals(status))
        {
            return adminOrder(id);
        }
        if (!"PENDING_SHIPMENT".equals(status) && !"PENDING_PAYMENT".equals(status))
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "只有待支付或待发货订单可以取消");
        }
        Map<String,Object> before=QicaiBusinessAudit.snapshot(jdbc,"order",id);
        payments.closeBeforeCancel(id, String.valueOf(rows.get(0).get("payment_status")));
        payments.releaseCancelledOrder(id);
        QicaiBusinessAudit.admin(jdbc,"CANCEL_ORDER","order",id,before);
        return adminOrder(id);
    }

    public List<Map<String, Object>> adminCouriers()
    {
        return jdbc.queryForList("SELECT id,phone,name,vehicle_no AS vehicleNo,status,available,version,created_at AS createdAt,updated_at AS updatedAt FROM qc_courier WHERE deleted=0 ORDER BY id");
    }

    @Transactional
    public Map<String, Object> createCourier(Map<String, Object> body)
    {
        String phone = QicaiAuthService.required(body, "phone", 20);
        if (!phone.matches("^1[3-9]\\d{9}$"))
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "请输入正确的11位手机号");
        }
        String password = QicaiAuthService.required(body, "password", 64);
        QicaiAuthService.validatePassword(password);
        String name = QicaiAuthService.required(body, "name", 60);
        String vehicleNo = QicaiAuthService.nullable(body, "vehicleNo", 32);
        String status = QicaiAuthService.required(body, "status", 20).toUpperCase();
        validateEnum(status, COURIER_STATUS, "配送员状态");
        boolean available = booleanValue(body, "available", true) && "ACTIVE".equals(status);
        jdbc.update("INSERT INTO qc_courier(phone,password_hash,name,vehicle_no,status,available) VALUES(?,?,?,?,?,?)",
                phone, passwordEncoder.encode(password), name, vehicleNo, status, available ? 1 : 0);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        QicaiBusinessAudit.admin(jdbc,"CREATE_COURIER","courier",id.longValue(),null);
        return adminCourier(id.longValue());
    }

    @Transactional
    public Map<String, Object> updateCourier(long id, Map<String, Object> body)
    {
        String phone = QicaiAuthService.required(body, "phone", 20);
        if (!phone.matches("^1[3-9]\\d{9}$"))
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "请输入正确的11位手机号");
        }
        String name = QicaiAuthService.required(body, "name", 60);
        String vehicleNo = QicaiAuthService.nullable(body, "vehicleNo", 32);
        String status = QicaiAuthService.required(body, "status", 20).toUpperCase();
        validateEnum(status, COURIER_STATUS, "配送员状态");
        lockCourier(id);
        Map<String,Object> before=QicaiBusinessAudit.snapshot(jdbc,"courier",id);
        if (!"ACTIVE".equals(status)) requireNoActiveTasks(id);
        int version = QicaiAuthService.number(body, "version", -1);
        if (version < 0)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "缺少配送员版本号");
        }
        boolean available = booleanValue(body, "available", true) && "ACTIVE".equals(status);
        String password = QicaiAuthService.nullable(body, "password", 64);
        if (password != null) QicaiAuthService.validatePassword(password);
        if (password != null && password.length() < 8)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "新密码至少需要8位");
        }
        int changed;
        if (password == null)
        {
            changed = jdbc.update("UPDATE qc_courier SET phone=?,name=?,vehicle_no=?,status=?,available=?,version=version+1 WHERE id=? AND version=? AND deleted=0",
                    phone, name, vehicleNo, status, available ? 1 : 0, id, version);
        }
        else
        {
            changed = jdbc.update("UPDATE qc_courier SET phone=?,password_hash=?,name=?,vehicle_no=?,status=?,available=?,version=version+1 WHERE id=? AND version=? AND deleted=0",
                    phone, passwordEncoder.encode(password), name, vehicleNo, status, available ? 1 : 0, id, version);
        }
        if (changed == 0)
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "配送员不存在或资料已被其他管理员修改");
        }
        if (!"ACTIVE".equals(status) || password != null)
        {
            jdbc.update("DELETE FROM qc_session WHERE principal_type='courier' AND principal_id=?", id);
        }
        QicaiBusinessAudit.admin(jdbc,"UPDATE_COURIER","courier",id,before);
        return adminCourier(id);
    }

    @Transactional
    public void deleteCourier(long id)
    {
        lockCourier(id);
        Map<String,Object> before=QicaiBusinessAudit.snapshot(jdbc,"courier",id);
        requireNoActiveTasks(id);
        int changed = jdbc.update("UPDATE qc_courier SET deleted=1,status='DISABLED',available=0,version=version+1 WHERE id=? AND deleted=0", id);
        if (changed == 0)
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "配送员不存在");
        }
        jdbc.update("DELETE FROM qc_session WHERE principal_type='courier' AND principal_id=?", id);
        QicaiBusinessAudit.admin(jdbc,"DELETE_COURIER","courier",id,before);
    }

    private void requireNoActiveTasks(long courierId)
    {
        // A current locking read must observe assignments committed while waiting
        // for the courier lock, even under MySQL REPEATABLE READ.
        List<Map<String, Object>> tasks = jdbc.queryForList("SELECT id FROM qc_delivery_task WHERE courier_id=? AND status<>'DELIVERED' LIMIT 1 FOR UPDATE", courierId);
        if (!tasks.isEmpty())
            throw new QicaiApiException(HttpStatus.CONFLICT, "配送员仍有未完成任务，请先完成或重新指派");
    }

    private Map<String, Object> lockCourier(long courierId)
    {
        return first(jdbc.queryForList("SELECT id,status,available FROM qc_courier WHERE id=? AND deleted=0 FOR UPDATE", courierId), "配送员不存在");
    }

    public Map<String, Object> adminDeliveries(String status, int pageNum, int pageSize)
    {
        pageNum = safePage(pageNum);
        pageSize = safeSize(pageSize);
        List<Object> args = new ArrayList<Object>();
        String filter = "";
        if (status != null && !status.trim().isEmpty())
        {
            validateEnum(status, DELIVERY_STATUS, "配送状态");
            filter = " WHERE d.status=?";
            args.add(status);
        }
        Map<String, Object> page = pagedQuery(deliverySelect() + filter + " ORDER BY d.id DESC",
                "SELECT COUNT(*) FROM qc_delivery_task d" + filter, args, pageNum, pageSize);
        for (Object row : (List<?>) page.get("rows")) addProofs((Map<String, Object>) row);
        return page;
    }

    @Transactional
    public Map<String, Object> assignDelivery(Map<String, Object> body)
    {
        long orderId = positiveLong(body, "orderId");
        long courierId = positiveLong(body, "courierId");
        String window = QicaiAuthService.nullable(body, "deliveryWindow", 80);
        String warehouseName = QicaiAuthService.required(body, "warehouseName", 120);
        String warehouseAddress = QicaiAuthService.required(body, "warehouseAddress", 255);
        BigDecimal distance = decimal(body, "distanceKm", false);
        if (distance.compareTo(new BigDecimal("999999.99")) > 0)
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "预计里程超出上限");
        Map<String, Object> courier = lockCourier(courierId);
        if (!"ACTIVE".equals(courier.get("status")) || ((Number) courier.get("available")).intValue() != 1)
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "配送员不存在或已停用");
        }
        List<Map<String, Object>> orders = jdbc.queryForList("SELECT status,payment_status FROM qc_order WHERE id=? AND deleted=0 FOR UPDATE", orderId);
        if (orders.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        String orderStatus = String.valueOf(orders.get(0).get("status"));
        if (!Arrays.asList("PAID", "NOT_REQUIRED", "NO_PAYMENT_REQUIRED").contains(String.valueOf(orders.get(0).get("payment_status"))))
            throw new QicaiApiException(HttpStatus.CONFLICT, "订单尚未支付或已关闭，不能指派配送");
        if (!"PENDING_SHIPMENT".equals(orderStatus) && !"ASSIGNED".equals(orderStatus))
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "当前订单状态不能分配配送任务");
        }
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT id,status FROM qc_delivery_task WHERE order_id=?", orderId);
        Map<String,Object> before=existing.isEmpty()?null:QicaiBusinessAudit.snapshot(jdbc,"delivery",((Number)existing.get(0).get("id")).longValue());
        if (!existing.isEmpty() && "EXCEPTION".equals(existing.get(0).get("status")))
            throw new QicaiApiException(HttpStatus.CONFLICT, "异常任务须由原配送员恢复后才能重新指派");
        long taskId;
        if (existing.isEmpty())
        {
            String taskNo = "QD" + LocalDateTime.now().format(NUMBER_TIME) + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            jdbc.update("INSERT INTO qc_delivery_task(task_no,order_id,courier_id,status,delivery_window,distance_km) VALUES(?,?,?,'ASSIGNED',?,?)",
                    taskNo, orderId, courierId, window, distance);
            taskId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class).longValue();
            addDeliveryEvent(taskId, "ASSIGNED", "运营人员已分配配送员");
        }
        else
        {
            taskId = ((Number) existing.get(0).get("id")).longValue();
            jdbc.update("UPDATE qc_delivery_task SET courier_id=?,status='ASSIGNED',delivery_window=?,distance_km=?,version=version+1 WHERE id=?",
                    courierId, window, distance, taskId);
            addDeliveryEvent(taskId, "ASSIGNED", "运营人员重新分配配送员");
        }
        jdbc.update("UPDATE qc_delivery_task SET warehouse_name=?,warehouse_address=? WHERE id=?", warehouseName, warehouseAddress, taskId);
        jdbc.update("UPDATE qc_order SET status='ASSIGNED',version=version+1 WHERE id=?", orderId);
        if(notices!=null && before!=null && before.get("courierId")!=null && ((Number)before.get("courierId")).longValue()!=courierId) {
            Long taskVersion=jdbc.queryForObject("SELECT version FROM qc_delivery_task WHERE id=?",Long.class,taskId);
            notices.transferred(orderId,((Number)before.get("courierId")).longValue(),taskVersion);
        }
        QicaiBusinessAudit.admin(jdbc,"ASSIGN_DELIVERY","delivery",taskId,before);
        return first(jdbc.queryForList(deliverySelect() + " WHERE d.id=?", taskId), "配送任务不存在");
    }

    public Map<String, Object> adminCoupons(String status, int pageNum, int pageSize)
    {
        pageNum = safePage(pageNum);
        pageSize = safeSize(pageSize);
        List<Object> args = new ArrayList<Object>();
        String filter = " WHERE deleted=0";
        if (status != null && !status.trim().isEmpty())
        {
            validateEnum(status, COUPON_STATUS, "优惠券状态");
            filter += " AND status=?";
            args.add(status);
        }
        return pagedQuery("SELECT id,name,audience,threshold_amount AS thresholdAmount,discount_amount AS discountAmount,valid_from AS validFrom,valid_to AS validTo,status,version,created_at AS createdAt FROM qc_coupon" + filter + " ORDER BY id DESC",
                "SELECT COUNT(*) FROM qc_coupon" + filter, args, pageNum, pageSize);
    }

    @Transactional
    public Map<String, Object> createCoupon(Map<String, Object> body)
    {
        CouponInput value = couponInput(body);
        jdbc.update("INSERT INTO qc_coupon(name,audience,threshold_amount,discount_amount,valid_from,valid_to,status) VALUES(?,?,?,?,?,?,?)",
                value.name, value.audience, value.threshold, value.discount, value.validFrom, value.validTo, value.status);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        QicaiBusinessAudit.admin(jdbc,"CREATE_COUPON","coupon",id.longValue(),null);
        return coupon(id.longValue());
    }

    @Transactional
    public Map<String, Object> updateCoupon(long id, Map<String, Object> body)
    {
        CouponInput value = couponInput(body);
        int version=QicaiAuthService.number(body,"version",-1);
        if (version<0) throw new QicaiApiException(HttpStatus.BAD_REQUEST,"缺少优惠券版本号，请刷新后重试");
        Map<String,Object> before=QicaiBusinessAudit.snapshot(jdbc,"coupon",id);
        int changed = jdbc.update("UPDATE qc_coupon SET name=?,audience=?,threshold_amount=?,discount_amount=?,valid_from=?,valid_to=?,status=?,version=version+1 WHERE id=? AND version=? AND deleted=0",
                value.name, value.audience, value.threshold, value.discount, value.validFrom, value.validTo, value.status, id, version);
        if (changed == 0)
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "优惠券已变化或不存在，请刷新后核对再保存");
        }
        QicaiBusinessAudit.admin(jdbc,"UPDATE_COUPON","coupon",id,before);
        return coupon(id);
    }

    @Transactional
    public void deleteCoupon(long id, int version)
    {
        if (version<0) throw new QicaiApiException(HttpStatus.BAD_REQUEST,"缺少优惠券版本号，请刷新后重试");
        Map<String,Object> before=QicaiBusinessAudit.snapshot(jdbc,"coupon",id);
        int changed = jdbc.update("UPDATE qc_coupon SET deleted=1,status='INACTIVE',version=version+1 WHERE id=? AND version=? AND deleted=0", id, version);
        if (changed == 0)
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "优惠券已变化或不存在，请刷新后核对再删除");
        }
        QicaiBusinessAudit.admin(jdbc,"DELETE_COUPON","coupon",id,before);
    }

    @Transactional
    public Map<String, Object> grantCoupon(long couponId, long customerId)
    {
        Map<String, Object> customer = first(jdbc.queryForList("SELECT audience,status FROM qc_customer WHERE id=? AND deleted=0 FOR UPDATE", customerId), "客户不存在");
        if (!"ACTIVE".equals(customer.get("status")))
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "当前客户状态不可发券");
        }
        List<Map<String, Object>> coupons = jdbc.queryForList("SELECT id FROM qc_coupon WHERE id=? AND deleted=0 AND status='ACTIVE' AND (audience='all' OR audience=?) AND NOW() BETWEEN valid_from AND valid_to FOR UPDATE", couponId, customer.get("audience"));
        if (coupons.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "优惠券不可用或不适用于当前客户");
        }
        int changed = jdbc.update("INSERT IGNORE INTO qc_customer_coupon(customer_id,coupon_id,status) VALUES(?,?,'AVAILABLE')", customerId, couponId);
        if (changed == 0)
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "该客户已拥有此优惠券");
        }
        Long grantId=jdbc.queryForObject("SELECT id FROM qc_customer_coupon WHERE customer_id=? AND coupon_id=?",Long.class,customerId,couponId);
        QicaiBusinessAudit.admin(jdbc,"GRANT_COUPON","customerCoupon",grantId,null);
        return first(jdbc.queryForList("SELECT id,customer_id AS customerId,coupon_id AS couponId,status,claimed_at AS claimedAt FROM qc_customer_coupon WHERE customer_id=? AND coupon_id=?",
                customerId, couponId), "发券记录不存在");
    }

    private Map<String, Object> requireApprovedCustomer(Principal principal)
    {
        Map<String, Object> customer = authService.customerProfile(principal.getId());
        String status = String.valueOf(customer.get("status"));
        if (!"ACTIVE".equals(status))
        {
            throw new QicaiApiException(HttpStatus.FORBIDDEN,
                    "PENDING".equals(status) ? "企业资料正在审核，审核通过后可查看企业价格" : "账号当前不可用");
        }
        return customer;
    }

    private Map<String, Object> adminProduct(long id)
    {
        return (Map<String,Object>)adminMedia(first(jdbc.queryForList(
                "SELECT id,sku,name," + productNameSql(true) + " AS businessName," + productNameSql(false) + " AS consumerName,subtitle,category,unit,COALESCE((SELECT u.business_unit FROM qc_product_unit u WHERE u.product_id=qc_product.id),unit) AS businessUnit,COALESCE((SELECT u.consumer_unit FROM qc_product_unit u WHERE u.product_id=qc_product.id),unit) AS consumerUnit,stock,business_price AS businessPrice,consumer_price AS consumerPrice,status,version,created_at AS createdAt,updated_at AS updatedAt FROM qc_product WHERE id=? AND deleted=0",
                id), "商品不存在"));
    }

    private Map<String, Object> coupon(long id)
    {
        return first(jdbc.queryForList(
                "SELECT id,name,audience,threshold_amount AS thresholdAmount,discount_amount AS discountAmount,valid_from AS validFrom,valid_to AS validTo,status,version,created_at AS createdAt FROM qc_coupon WHERE id=? AND deleted=0",
                id), "优惠券不存在");
    }

    private Map<String, Object> adminCourier(long id)
    {
        return first(jdbc.queryForList(
                "SELECT id,phone,name,vehicle_no AS vehicleNo,status,available,version,created_at AS createdAt,updated_at AS updatedAt FROM qc_courier WHERE id=? AND deleted=0",
                id), "配送员不存在");
    }

    private boolean booleanValue(Map<String, Object> body, String key, boolean defaultValue)
    {
        Object raw = body.get(key);
        if (raw == null)
        {
            return defaultValue;
        }
        if (!(raw instanceof Boolean))
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, key + "必须为布尔值");
        }
        return ((Boolean) raw).booleanValue();
    }

    private String productNameSql(boolean business) {
        return "COALESCE((SELECT n."+(business?"business_name":"consumer_name")+" FROM qc_product_name n WHERE n.product_id=qc_product.id),name)";
    }
    private void saveProductNames(long id,Map<String,Object> body) {
        if(!body.containsKey("businessName") && !body.containsKey("consumerName"))return;
        String business=body.containsKey("businessName")?QicaiAuthService.required(body,"businessName",160):null;
        String consumer=body.containsKey("consumerName")?QicaiAuthService.required(body,"consumerName",160):null;
        jdbc.update("INSERT INTO qc_product_name(product_id,business_name,consumer_name) VALUES(?,?,?) ON DUPLICATE KEY UPDATE business_name=COALESCE(VALUES(business_name),business_name),consumer_name=COALESCE(VALUES(consumer_name),consumer_name)",id,business,consumer);
    }

    private String audienceUnitSql(Principal principal)
    {
        return "COALESCE((SELECT u." + ("business".equals(principal.getAudience()) ? "business_unit" : "consumer_unit")
                + " FROM qc_product_unit u WHERE u.product_id=qc_product.id),unit)";
    }

    private void saveProductUnits(long id, Map<String,Object> body)
    {
        if (!body.containsKey("businessUnit") && !body.containsKey("consumerUnit")) return;
        String business = body.containsKey("businessUnit") ? QicaiAuthService.required(body,"businessUnit",20) : null;
        String consumer = body.containsKey("consumerUnit") ? QicaiAuthService.required(body,"consumerUnit",20) : null;
        jdbc.update("INSERT INTO qc_product_unit(product_id,business_unit,consumer_unit) VALUES(?,?,?) ON DUPLICATE KEY UPDATE business_unit=COALESCE(VALUES(business_unit),business_unit),consumer_unit=COALESCE(VALUES(consumer_unit),consumer_unit)", id,business,consumer);
    }

    private ProductInput productInput(Map<String, Object> body)
    {
        ProductInput value = new ProductInput();
        value.sku = QicaiAuthService.required(body, "sku", 64).toUpperCase();
        value.name = QicaiAuthService.required(body, "name", 160);
        value.subtitle = QicaiAuthService.nullable(body, "subtitle", 255);
        value.category = QicaiAuthService.required(body, "category", 60);
        value.unit = QicaiAuthService.required(body, "unit", 20);
        value.stock = QicaiInventory.input(body.get("stock"));
        if (value.stock < 0)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "库存不能小于0");
        }
        value.businessPrice = decimal(body, "businessPrice", true);
        value.consumerPrice = decimal(body, "consumerPrice", true);
        value.status = QicaiAuthService.required(body, "status", 20).toUpperCase();
        validateEnum(value.status, PRODUCT_STATUS, "商品状态");
        return value;
    }

    private CouponInput couponInput(Map<String, Object> body)
    {
        CouponInput value = new CouponInput();
        value.name = QicaiAuthService.required(body, "name", 120);
        value.audience = QicaiAuthService.required(body, "audience", 16);
        if (!Arrays.asList("all", "business", "consumer").contains(value.audience))
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "优惠券适用账号类型不正确");
        }
        value.threshold = decimal(body, "thresholdAmount", false);
        value.discount = decimal(body, "discountAmount", true);
        value.validFrom = QicaiAuthService.required(body, "validFrom", 32);
        value.validTo = QicaiAuthService.required(body, "validTo", 32);
        try
        {
            if (!LocalDateTime.parse(value.validTo.replace(" ", "T")).isAfter(LocalDateTime.parse(value.validFrom.replace(" ", "T"))))
            {
                throw new QicaiApiException(HttpStatus.BAD_REQUEST, "优惠券结束时间必须晚于开始时间");
            }
        }
        catch (java.time.format.DateTimeParseException ex)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "优惠券时间格式不正确");
        }
        value.status = QicaiAuthService.required(body, "status", 20).toUpperCase();
        validateEnum(value.status, COUPON_STATUS, "优惠券状态");
        return value;
    }

    private Object boundedPage(String listSql, String countSql, List<Object> listArgs, List<Object> countArgs,
            Integer pageNum, Integer pageSize)
    {
        if (pageNum==null && pageSize==null)
        {
            List<Map<String,Object>> rows=jdbc.queryForList(listSql+" LIMIT 101",listArgs.toArray());
            if (rows.size()>100) throw new QicaiApiException(HttpStatus.BAD_REQUEST,"列表超过100条，请更新客户端并使用分页查询");
            return rows;
        }
        int number=pageNum==null?1:pageNum.intValue(), size=pageSize==null?20:pageSize.intValue();
        if (number<1 || size<1 || size>100)
            throw new QicaiApiException(HttpStatus.BAD_REQUEST,"pageNum必须为正整数，pageSize必须在1至100之间");
        Long total=jdbc.queryForObject(countSql,Long.class,countArgs.toArray());
        List<Object> args=new ArrayList<Object>(listArgs);args.add(size);args.add(((long)number-1)*size);
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        result.put("rows",jdbc.queryForList(listSql+" LIMIT ? OFFSET ?",args.toArray()));
        result.put("total",total==null?0L:total);result.put("pageNum",number);result.put("pageSize",size);
        return result;
    }

    private LocationInput location(Map<String,Object> body, boolean required)
    {
        LocationInput location=new LocationInput();
        if (body.containsKey("coordinateSystem") && !"GCJ-02".equals(body.get("coordinateSystem")))
            throw new QicaiApiException(HttpStatus.BAD_REQUEST,"收货地点仅接受微信GCJ-02坐标");
        if (!required && !body.containsKey("deliveryLatitude") && !body.containsKey("deliveryLongitude") && !body.containsKey("deliveryLocationName"))
            return location;
        location.latitude=coordinate(body.get("deliveryLatitude"),90);
        location.longitude=coordinate(body.get("deliveryLongitude"),180);
        Object name=body.get("deliveryLocationName");
        if (!(name instanceof String) || ((String)name).trim().isEmpty() || ((String)name).length()>120)
            throw new QicaiApiException(HttpStatus.BAD_REQUEST,"请选择有效的收货地点，地点名称不能超过120字");
        location.name=((String)name).trim();
        return location;
    }

    private BigDecimal coordinate(Object raw, int limit)
    {
        try
        {
            if (!(raw instanceof Number) || raw instanceof Double || raw instanceof Float) throw new ArithmeticException();
            BigDecimal value=new BigDecimal(raw.toString());
            if (value.abs().compareTo(BigDecimal.valueOf(limit))>0) throw new ArithmeticException();
            // Reject impossible precision before setScale can expand an extreme
            // exponent. Canonical zero/trailing zeros remain valid exact values.
            value=value.stripTrailingZeros();
            if (value.scale()>7) throw new ArithmeticException();
            return value.setScale(7,RoundingMode.UNNECESSARY);
        }
        catch (RuntimeException ex) { throw new QicaiApiException(HttpStatus.BAD_REQUEST,"收货经纬度必须为有效数值，最多保留7位小数"); }
    }

    private static class LocationInput
    {
        BigDecimal latitude;
        BigDecimal longitude;
        String name;
    }

    private Map<String, Object> pagedQuery(String listSql, String countSql, List<Object> args, int pageNum, int pageSize)
    {
        Integer total = jdbc.queryForObject(countSql, Integer.class, args.toArray());
        List<Object> pagedArgs = new ArrayList<Object>(args);
        pagedArgs.add(pageSize);
        pagedArgs.add(((long) pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbc.queryForList(listSql + " LIMIT ? OFFSET ?", pagedArgs.toArray());
        return page(rows, total == null ? 0 : total.intValue(), pageNum, pageSize);
    }

    private Map<String, Object> page(List<Map<String, Object>> rows, int total, int pageNum, int pageSize)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("rows", rows);
        result.put("total", total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        return result;
    }

    private String deliverySelect()
    {
        return "SELECT d.id,d.task_no AS taskNo,d.order_id AS orderId,d.courier_id AS courierId,d.status,d.exception_from AS exceptionFrom,d.warehouse_name AS warehouseName,d.warehouse_address AS warehouseAddress,d.delivery_window AS deliveryWindow,d.distance_km AS distanceKm,d.pickup_proof_url AS pickupProofUrl,d.delivery_proof_url AS deliveryProofUrl,d.signed_name AS signedName,d.version,d.created_at AS createdAt,o.order_no AS orderNo,o.contact_name AS contactName,o.contact_phone AS contactPhone,o.delivery_address AS deliveryAddress,o.delivery_latitude AS deliveryLatitude,o.delivery_longitude AS deliveryLongitude,o.delivery_location_name AS deliveryLocationName,'GCJ-02' AS coordinateSystem,o.payable_amount AS payableAmount,c.name AS courierName,c.phone AS courierPhone,c.vehicle_no AS vehicleNo FROM qc_delivery_task d JOIN qc_order o ON o.id=d.order_id LEFT JOIN qc_courier c ON c.id=d.courier_id";
    }

    private void addDeliveryEvent(long taskId, String status, String note)
    {
        addDeliveryEvent(taskId,status,note,null);
    }

    private void addDeliveryEvent(long taskId, String status, String note, Principal principal)
    {
        addDeliveryEvent(taskId,status,note,principal,false);
    }
    private void addDeliveryEvent(long taskId, String status, String note, Principal principal, boolean resumed)
    {
        String actorType=principal==null?"admin":principal.getType();
        long actorId=principal==null?com.ruoyi.common.utils.SecurityUtils.getUserId():principal.getId();
        String actorName=principal==null?com.ruoyi.common.utils.SecurityUtils.getUsername():actorType+":"+actorId;
        String value=note==null||note.trim().isEmpty()?status:note.trim();
        if (value.length()>512) throw new QicaiApiException(HttpStatus.BAD_REQUEST,"配送事件备注过长");
        jdbc.update("INSERT INTO qc_delivery_event(delivery_id,status,note,actor_type,actor_id,actor_name) VALUES(?,?,?,?,?,?)",
                taskId,status,value,actorType,actorId,actorName);
        if(notices!=null) {
            Long orderId=jdbc.queryForObject("SELECT order_id FROM qc_delivery_task WHERE id=?",Long.class,taskId);
            notices.orderEvent(orderId,resumed?"RESUMED":status);
        }
    }

    public Map<String,Object> adminAudit(String objectType, Long objectId, int pageNum, int pageSize)
    {
        if (pageNum<1 || pageSize<1 || pageSize>100) throw new QicaiApiException(HttpStatus.BAD_REQUEST,"分页参数不正确");
        String filter=" WHERE 1=1";List<Object> args=new ArrayList<Object>();
        if (objectType!=null && !objectType.trim().isEmpty())
        {
            validateEnum(objectType,Arrays.asList("product","coupon","customer","courier","order","delivery","customerCoupon"),"审计对象");
            filter+=" AND object_type=?";args.add(objectType);
        }
        if (objectId!=null) {if(objectId.longValue()<1)throw new QicaiApiException(HttpStatus.BAD_REQUEST,"审计对象编号不正确");filter+=" AND object_id=?";args.add(objectId);}
        return pagedQuery("SELECT id,actor_type AS actorType,actor_id AS actorId,actor_name AS actorName,action,object_type AS objectType,object_id AS objectId,before_values AS beforeValues,after_values AS afterValues,related_event_id AS relatedEventId,created_at AS createdAt FROM qc_business_audit"+filter+" ORDER BY id DESC",
                "SELECT COUNT(*) FROM qc_business_audit"+filter,args,pageNum,pageSize);
    }

    private void saveProofs(long taskId, String phase, List<String> files)
    {
        for (String file : files) jdbc.update("INSERT IGNORE INTO qc_delivery_attachment(delivery_id,phase,file_name) VALUES(?,?,?)", taskId, phase, file);
    }

    private void addProofs(Map<String, Object> delivery)
    {
        for (String phase : Arrays.asList("PICKUP", "DELIVERY"))
        {
            List<String> paths = jdbc.queryForList("SELECT file_name FROM qc_delivery_attachment WHERE delivery_id=? AND phase=? ORDER BY id", String.class, delivery.get("id"), phase);
            String key = "PICKUP".equals(phase) ? "pickupProofUrl" : "deliveryProofUrl";
            if (paths.isEmpty() && delivery.get(key) != null) paths.add(String.valueOf(delivery.get(key)));
            delivery.put(key + "s", paths);
        }
    }

    private boolean validDeliveryTransition(String current, String requested)
    {
        return ("ASSIGNED".equals(current) && "PICKED_UP".equals(requested))
                || ("PICKED_UP".equals(current) && "IN_DELIVERY".equals(requested))
                || ("IN_DELIVERY".equals(current) && "DELIVERED".equals(requested))
                || (!"DELIVERED".equals(current) && "EXCEPTION".equals(requested));
    }

    private long positiveLong(Map<String, Object> body, String key)
    {
        Object raw = body.get(key);
        try
        {
            long value = Long.parseLong(String.valueOf(raw));
            if (value <= 0)
            {
                throw new NumberFormatException();
            }
            return value;
        }
        catch (Exception ex)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, key + "必须为正整数");
        }
    }

    private BigDecimal decimal(Map<String, Object> body, String key, boolean positive)
    {
        Object raw = body.get(key);
        try
        {
            BigDecimal value = new BigDecimal(String.valueOf(raw)).setScale(2, RoundingMode.HALF_UP);
            if ((positive && value.compareTo(BigDecimal.ZERO) <= 0) || (!positive && value.compareTo(BigDecimal.ZERO) < 0))
            {
                throw new NumberFormatException();
            }
            if (value.compareTo(new BigDecimal("9999999999.99")) > 0)
            {
                throw new NumberFormatException();
            }
            return value;
        }
        catch (Exception ex)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, key + "金额格式不正确");
        }
    }

    private BigDecimal money(Object value)
    {
        return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP);
    }

    private int safePage(int pageNum)
    {
        return Math.max(1, pageNum);
    }

    private int safeSize(int pageSize)
    {
        return Math.max(1, Math.min(100, pageSize));
    }

    private long scalar(String sql)
    {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value.longValue();
    }

    private void validateEnum(String value, List<String> allowed, String label)
    {
        if (!allowed.contains(value))
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, label + "不正确");
        }
    }

    private Map<String, Object> first(List<Map<String, Object>> rows, String message)
    {
        if (rows.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, message);
        }
        return rows.get(0);
    }

    private static class ProductInput
    {
        String sku;
        String name;
        String subtitle;
        String category;
        String unit;
        long stock;
        BigDecimal businessPrice;
        BigDecimal consumerPrice;
        String status;
    }

    private static class CouponInput
    {
        String name;
        String audience;
        BigDecimal threshold;
        BigDecimal discount;
        String validFrom;
        String validTo;
        String status;
    }
}

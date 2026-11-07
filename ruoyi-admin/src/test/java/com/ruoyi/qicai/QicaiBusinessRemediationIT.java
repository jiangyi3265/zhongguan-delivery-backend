package com.ruoyi.qicai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.RuoYiApplication;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.util.LinkedMultiValueMap;
import static org.junit.jupiter.api.Assertions.*;

/** Actual isolated HTTP/MySQL/Redis and uploads. No WeChat or production traffic. */
@SpringBootTest(classes=RuoYiApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"server.address=127.0.0.1","qicai.wechat-pay.enabled=false","qicai.wechat-login.enabled=false",
                "qicai.wechat-pay.maintenance-enabled=false","qicai.sms.dev-mode=false",
                "spring.redis.host=127.0.0.1","spring.redis.port=16399","spring.devtools.restart.enabled=false"})
@ActiveProfiles("qicai")
@ContextConfiguration(initializers=QicaiCatalogIT.IsolatedEnvironment.class)
class QicaiBusinessRemediationIT
{
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired BCryptPasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    final List<Long> products=new ArrayList<>(),customers=new ArrayList<>(),couriers=new ArrayList<>(),orders=new ArrayList<>(),coupons=new ArrayList<>();
    final List<String> uploads=new ArrayList<>();
    String tag,admin,consumer,otherConsumer,driver,business,pending;
    long customer,otherCustomer,courier,product;
    final String password="SyntheticBusiness2026!";

    static Map<String,Object> map(Object... kv) {Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)result.put((String)kv[i],kv[i+1]);return result;}
    ResponseEntity<JsonNode> call(HttpMethod method,String path,Object body,String token,boolean administrator)
    {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null)headers.set(administrator?"Authorization":"X-Qicai-Token",administrator?"Bearer "+token:token);
        return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
    JsonNode ok(ResponseEntity<JsonNode> response,int status)
    {
        assertEquals(status,response.getStatusCodeValue(),()->String.valueOf(response.getBody()));
        assertEquals(200,response.getBody().path("code").asInt());return response.getBody().path("data");
    }
    JsonNode a(HttpMethod method,String path,Object body){return ok(call(method,"/qicai/admin"+path,body,admin,true),200);}
    JsonNode m(HttpMethod method,String path,Object body,String token){return ok(call(method,"/qicai/mobile"+path,body,token,false),method==HttpMethod.POST&&path.equals("/orders")?201:200);}
    Map<String,Object> body(JsonNode value){return json.convertValue(value,LinkedHashMap.class);}
    String login(long id,String audience)
    {
        String phone=jdbc.queryForObject("SELECT phone FROM qc_customer WHERE id=?",String.class,id);
        return m(HttpMethod.POST,"/auth/login",map("phone",phone,"password",password,"audience",audience),null).path("token").asText();
    }
    long customer(String audience,String status)
    {
        String phone="176"+String.format(Locale.ROOT,"%08d",Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),99999999L));
        jdbc.update("INSERT INTO qc_customer(audience,phone,password_hash,company_name,contact_name,address,status) VALUES(?,?,?,?,'synthetic-contact','synthetic-address',?)",audience,phone,passwords.encode(password),tag,status);
        long id=jdbc.queryForObject("SELECT id FROM qc_customer WHERE audience=? AND phone=?",Long.class,audience,phone);customers.add(id);return id;
    }
    JsonNode product(long id){return a(HttpMethod.GET,"/products?keyword="+tag+"&pageSize=100",null).path("rows").findParents("sku").stream().filter(x->x.path("id").asLong()==id).findFirst().orElseThrow(()->new AssertionError("Fixture product missing"));}
    JsonNode order(Map<String,Object> extra,String token)
    {
        Map<String,Object> request=map("productId",product,"quantity",1,"idempotencyKey",UUID.randomUUID().toString(),"deliveryAddress","original-address","contactName","synthetic-contact","contactPhone","13900000000");request.putAll(extra);
        JsonNode result=m(HttpMethod.POST,"/orders",request,token);if(!orders.contains(result.path("id").asLong()))orders.add(result.path("id").asLong());return result;
    }
    long assign(long id){return a(HttpMethod.POST,"/deliveries/assign",map("orderId",id,"courierId",courier,"warehouseName","synthetic-warehouse","warehouseAddress","synthetic-source","distanceKm",1)).path("id").asLong();}
    long stock(){return jdbc.queryForObject("SELECT stock FROM qc_product WHERE id=?",Long.class,product);}
    long auditCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM qc_business_audit WHERE object_type='product' AND object_id=?",Long.class,product);}
    String text(int length){char[] value=new char[length];Arrays.fill(value,'备');return new String(value);}

    @BeforeEach void setup()
    {
        assertEquals(13319,jdbc.queryForObject("SELECT @@port",Integer.class));assertEquals("qicai_acceptance",jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(Paths.get(System.getenv("QICAI_RUNTIME"),"mysql").toAbsolutePath().normalize(),Paths.get(jdbc.queryForObject("SELECT @@datadir",String.class)).toAbsolutePath().normalize());
        tag="REM-"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        ResponseEntity<JsonNode> login=call(HttpMethod.POST,"/login",map("username","admin","password",System.getenv("QICAI_ADMIN_PASSWORD")),null,false);
        assertEquals(200,login.getStatusCodeValue());admin=login.getBody().path("token").asText();assertFalse(admin.isEmpty());
        customer=customer("consumer","ACTIVE");otherCustomer=customer("consumer","ACTIVE");consumer=login(customer,"consumer");otherConsumer=login(otherCustomer,"consumer");
        long enterprise=customer("business","ACTIVE"),unapproved=customer("business","PENDING");business=login(enterprise,"business");pending=login(unapproved,"business");
        product=a(HttpMethod.POST,"/products",map("sku",tag,"name",tag,"category",tag,"unit","item","stock",1000,"businessPrice",1,"consumerPrice",2,"status","ACTIVE")).path("id").asLong();products.add(product);
        String phone="177"+String.format(Locale.ROOT,"%08d",Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),99999999L));
        courier=a(HttpMethod.POST,"/couriers",map("phone",phone,"password",password,"name",tag,"vehicleNo","TEST","status","ACTIVE","available",true)).path("id").asLong();couriers.add(courier);
        driver=m(HttpMethod.POST,"/courier/login",map("phone",phone,"password",password),null).path("token").asText();
    }

    @AfterEach void cleanup() throws Exception
    {
        for(long id:orders)
        {
            for(long task:jdbc.queryForList("SELECT id FROM qc_delivery_task WHERE order_id=?",Long.class,id))
            {
                jdbc.update("DELETE FROM qc_business_audit WHERE object_type='delivery' AND object_id=?",task);
                jdbc.update("DELETE FROM qc_delivery_attachment WHERE delivery_id=?",task);jdbc.update("DELETE FROM qc_delivery_event WHERE delivery_id=?",task);jdbc.update("DELETE FROM qc_delivery_task WHERE id=?",task);
            }
            jdbc.update("DELETE FROM qc_business_audit WHERE object_type='order' AND object_id=?",id);
            jdbc.update("UPDATE qc_customer_coupon SET used_order_id=NULL WHERE used_order_id=?",id);
            jdbc.update("DELETE FROM qc_payment_maintenance WHERE order_id=?",id);jdbc.update("DELETE FROM qc_order_item WHERE order_id=?",id);jdbc.update("DELETE FROM qc_order WHERE id=?",id);
        }
        for(long id:customers)
        {
            for(long grant:jdbc.queryForList("SELECT id FROM qc_customer_coupon WHERE customer_id=?",Long.class,id))jdbc.update("DELETE FROM qc_business_audit WHERE object_type='customerCoupon' AND object_id=?",grant);
            jdbc.update("DELETE FROM qc_customer_coupon WHERE customer_id=?",id);jdbc.update("DELETE FROM qc_business_audit WHERE object_type='customer' AND object_id=?",id);
            jdbc.update("DELETE FROM qc_session WHERE principal_type='customer' AND principal_id=?",id);jdbc.update("DELETE FROM qc_customer WHERE id=?",id);
        }
        for(long id:coupons){jdbc.update("DELETE FROM qc_business_audit WHERE object_type='coupon' AND object_id=?",id);jdbc.update("DELETE FROM qc_coupon WHERE id=?",id);}
        for(long id:products){jdbc.update("DELETE FROM qc_business_audit WHERE object_type='product' AND object_id=?",id);jdbc.update("DELETE FROM qc_product WHERE id=?",id);}
        for(long id:couriers){jdbc.update("DELETE FROM qc_business_audit WHERE object_type='courier' AND object_id=?",id);jdbc.update("DELETE FROM qc_session WHERE principal_type='courier' AND principal_id=?",id);jdbc.update("DELETE FROM qc_courier WHERE id=?",id);}
        Path root=Paths.get(System.getenv("QICAI_RUNTIME"),"uploads").toAbsolutePath().normalize();
        for(String upload:uploads){jdbc.update("DELETE FROM qc_upload WHERE file_name=?",upload);assertTrue(upload.startsWith("/profile/"));Path file=root.resolve(upload.substring("/profile/".length())).normalize();assertTrue(file.startsWith(root));Files.deleteIfExists(file);}
    }

    String upload(String token) throws Exception
    {
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        ByteArrayResource resource=new ByteArrayResource(bytes.toByteArray()){@Override public String getFilename(){return "synthetic-proof.png";}};
        HttpHeaders part=new HttpHeaders();part.setContentType(MediaType.IMAGE_PNG);
        LinkedMultiValueMap<String,Object> data=new LinkedMultiValueMap<>();data.add("file",new HttpEntity<>(resource,part));
        HttpHeaders headers=new HttpHeaders();headers.set("X-Qicai-Token",token);headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        JsonNode result=ok(http.postForEntity("/qicai/mobile/upload",new HttpEntity<>(data,headers),JsonNode.class),201);
        String file=result.path("fileName").asText();assertTrue(file.startsWith("/profile/"));uploads.add(file);return file;
    }

    @Test void receiptBoundaryPersistsCompleteNoteProofActorAndIdempotency() throws Exception
    {
        String buyerProof=upload(consumer),driverProof=upload(driver);
        for(int length:new int[]{248,249,255,256})
        {
            long id=order(map(),consumer).path("id").asLong(),task=assign(id);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_delivery_event WHERE delivery_id=? AND status='ASSIGNED' AND actor_type='admin' AND actor_id=1",Integer.class,task));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_business_audit a JOIN qc_delivery_event e ON e.id=a.related_event_id WHERE a.object_type='delivery' AND a.object_id=? AND e.delivery_id=?",Integer.class,task,task));
            m(HttpMethod.PUT,"/courier/tasks/"+task+"/status",map("status","PICKED_UP","pickupProofUrls",Arrays.asList(driverProof)),driver);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_delivery_event WHERE delivery_id=? AND status='PICKED_UP' AND actor_type='courier' AND actor_id=?",Integer.class,task,courier));
            m(HttpMethod.PUT,"/courier/tasks/"+task+"/status",map("status","IN_DELIVERY"),driver);
            Map<String,Object> before=jdbc.queryForMap("SELECT status,version FROM qc_order WHERE id=?",id);
            Map<String,Object> taskBefore=jdbc.queryForMap("SELECT status,version,signed_name,delivery_proof_url FROM qc_delivery_task WHERE id=?",task);
            long attachments=jdbc.queryForObject("SELECT COUNT(*) FROM qc_delivery_attachment WHERE delivery_id=?",Long.class,task);
            long events=jdbc.queryForObject("SELECT COUNT(*) FROM qc_delivery_event WHERE delivery_id=?",Long.class,task);
            ResponseEntity<JsonNode> result=call(HttpMethod.POST,"/qicai/mobile/orders/"+id+"/receipt",map("signedName","synthetic","proofUrls",Arrays.asList(buyerProof),"remark",text(length)),consumer,false);
            if(length==256){assertEquals(400,result.getStatusCodeValue());assertEquals(before,jdbc.queryForMap("SELECT status,version FROM qc_order WHERE id=?",id));assertEquals(taskBefore,jdbc.queryForMap("SELECT status,version,signed_name,delivery_proof_url FROM qc_delivery_task WHERE id=?",task));assertEquals(attachments,jdbc.queryForObject("SELECT COUNT(*) FROM qc_delivery_attachment WHERE delivery_id=?",Long.class,task));assertEquals(events,jdbc.queryForObject("SELECT COUNT(*) FROM qc_delivery_event WHERE delivery_id=?",Long.class,task));continue;}
            assertEquals("COMPLETED",ok(result,200).path("status").asText());
            Map<String,Object> event=jdbc.queryForMap("SELECT note,actor_type,actor_id FROM qc_delivery_event WHERE delivery_id=? ORDER BY id DESC LIMIT 1",task);
            assertEquals("客户确认收货："+text(length),event.get("note"));assertEquals("customer",event.get("actor_type"));assertEquals(customer,((Number)event.get("actor_id")).longValue());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_delivery_attachment WHERE delivery_id=? AND phase='DELIVERY' AND file_name=?",Integer.class,task,buyerProof));
            m(HttpMethod.POST,"/orders/"+id+"/receipt",map("signedName","synthetic","proofUrls",Arrays.asList(buyerProof),"remark",text(length)),consumer);
            assertEquals(events+1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_delivery_event WHERE delivery_id=?",Long.class,task));
            assertEquals("COMPLETED",m(HttpMethod.GET,"/orders/"+id,null,login(customer,"consumer")).path("status").asText());
            assertEquals("DELIVERED",m(HttpMethod.GET,"/courier/tasks/"+task,null,driver).path("status").asText());
        }
    }

    @Test void inventoryRecoversOldIntBoundaryAndReservesSafeIntegerHeadroom() throws Exception
    {
        long id=order(map(),consumer).path("id").asLong();Map<String,Object> p=body(product(product));p.put("stock",2147483647L);
        a(HttpMethod.PUT,"/products/"+product,p);a(HttpMethod.POST,"/orders/"+id+"/cancel",map());assertEquals(2147483648L,stock());
        long pendingOrder=order(map(),consumer).path("id").asLong();p=body(product(product));p.put("stock",QicaiInventory.MAX_STOCK);
        Map<String,Object> before=jdbc.queryForMap("SELECT stock,version FROM qc_product WHERE id=?",product);long audits=auditCount();
        assertEquals(409,call(HttpMethod.PUT,"/qicai/admin/products/"+product,p,admin,true).getStatusCodeValue());assertEquals(before,jdbc.queryForMap("SELECT stock,version FROM qc_product WHERE id=?",product));assertEquals(audits,auditCount());
        p.put("stock",QicaiInventory.MAX_STOCK-1);a(HttpMethod.PUT,"/products/"+product,p);a(HttpMethod.POST,"/orders/"+pendingOrder+"/cancel",map());assertEquals(QicaiInventory.MAX_STOCK,stock());
        Map<String,Object> cancellation=jdbc.queryForMap("SELECT actor_type,actor_id,actor_name,before_values,after_values FROM qc_business_audit WHERE object_type='order' AND object_id=? AND action='CANCEL_ORDER'",pendingOrder);
        assertEquals("admin",cancellation.get("actor_type"));assertEquals(1L,((Number)cancellation.get("actor_id")).longValue());assertEquals("admin",cancellation.get("actor_name"));
        JsonNode prior=json.readTree(String.valueOf(cancellation.get("before_values"))),after=json.readTree(String.valueOf(cancellation.get("after_values")));
        assertEquals("PENDING_SHIPMENT",prior.path("status").asText());assertEquals("CANCELLED",after.path("status").asText());
        assertEquals("NOT_REQUIRED",prior.path("paymentStatus").asText());assertEquals("NOT_REQUIRED",after.path("paymentStatus").asText());assertEquals(prior.path("version").asInt()+1,after.path("version").asInt());
        a(HttpMethod.POST,"/orders/"+pendingOrder+"/cancel",map());assertEquals(QicaiInventory.MAX_STOCK,stock());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_business_audit WHERE object_type='order' AND object_id=? AND action='CANCEL_ORDER'",Integer.class,pendingOrder),"Idempotent cancellation must not add another administrative change record");
    }

    @Test void concurrentOrderAndStockEditNeverConsumeReservedRecoveryCapacity() throws Exception
    {
        Map<String,Object> initial=body(product(product));initial.put("stock",QicaiInventory.MAX_STOCK);JsonNode updated=a(HttpMethod.PUT,"/products/"+product,initial);
        Map<String,Object> edit=body(updated);edit.put("stock",QicaiInventory.MAX_STOCK);edit.put("version",updated.path("version").asInt()+1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try(java.sql.Connection blocker=jdbc.getDataSource().getConnection())
        {
            blocker.setAutoCommit(false);try(java.sql.PreparedStatement statement=blocker.prepareStatement("SELECT id FROM qc_product WHERE id=? FOR UPDATE")){statement.setLong(1,product);try(java.sql.ResultSet rows=statement.executeQuery()){assertTrue(rows.next());}}
            Future<JsonNode> placed=pool.submit(()->order(map(),consumer));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean waiting=false;
            while(System.nanoTime()<deadline)
            {
                Long count=jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='qc_product' AND l.INDEX_NAME='PRIMARY' AND l.LOCK_DATA=?",Long.class,String.valueOf(product));
                if(count!=null&&count>0){waiting=true;break;}Thread.sleep(20);
            }
            assertTrue(waiting,"The order must actually wait on the held product row");
            Future<ResponseEntity<JsonNode>> edited=pool.submit(()->call(HttpMethod.PUT,"/qicai/admin/products/"+product,edit,admin,true));
            blocker.commit();JsonNode created=placed.get(15,TimeUnit.SECONDS);assertTrue(created.path("id").asLong()>0);
            ResponseEntity<JsonNode> conflict=edited.get(15,TimeUnit.SECONDS);assertEquals(409,conflict.getStatusCodeValue());
            assertEquals("当前未完成订单仍占用库存，请预留取消或退款的回补空间",conflict.getBody().path("msg").asText(),"A stale version rejection must not masquerade as the reservation-capacity check");
            assertEquals(QicaiInventory.MAX_STOCK-1,stock());assertEquals(1,jdbc.queryForObject("SELECT quantity FROM qc_order_item WHERE order_id=?",Integer.class,created.path("id").asLong()));
            a(HttpMethod.POST,"/orders/"+created.path("id").asLong()+"/cancel",map());assertEquals(QicaiInventory.MAX_STOCK,stock());
        }
        finally{pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
    }

    @Test void rawJsonInventoryNeverAcceptsRoundedFractionOrOverflowAndKeepsStateUnchanged() throws Exception
    {
        Map<String,Object> update=body(product(product));update.remove("stock");
        String updateBase=json.writeValueAsString(update);
        Map<String,Object> create=map("sku",tag+"-raw","name",tag,"category",tag,"unit","item","businessPrice",1,"consumerPrice",2,"status","ACTIVE");
        String createBase=json.writeValueAsString(create);
        Map<String,Object> before=jdbc.queryForMap("SELECT stock,version FROM qc_product WHERE id=?",product);long audits=auditCount();
        for(String number:Arrays.asList("9007199254740990.5","9007199254740990.9","9007199254740991.1","9007199254740992","1.0","1e3"))
        {
            // Exact raw JSON is essential: serializing a Java Double first would
            // erase the fractional input and fail to exercise the real defect.
            String updateJson=updateBase.substring(0,updateBase.length()-1)+",\"stock\":"+number+"}";
            assertEquals(400,call(HttpMethod.PUT,"/qicai/admin/products/"+product,updateJson,admin,true).getStatusCodeValue(),number);
            String createJson=createBase.substring(0,createBase.length()-1)+",\"stock\":"+number+"}";
            assertEquals(400,call(HttpMethod.POST,"/qicai/admin/products",createJson,admin,true).getStatusCodeValue(),number);
            assertEquals(before,jdbc.queryForMap("SELECT stock,version FROM qc_product WHERE id=?",product));assertEquals(audits,auditCount());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qc_product WHERE sku=?",Integer.class,tag+"-raw"));
        }
        for(String number:Arrays.asList("2147483648","9007199254740991","\"12\""))
        {
            update=body(product(product));update.remove("stock");updateBase=json.writeValueAsString(update);
            String exact=updateBase.substring(0,updateBase.length()-1)+",\"stock\":"+number+"}";
            ok(call(HttpMethod.PUT,"/qicai/admin/products/"+product,exact,admin,true),200);
            assertEquals(Long.parseLong(number.replace("\"","")),stock());
        }
    }

    JsonNode coupon()
    {
        JsonNode c=a(HttpMethod.POST,"/coupons",map("name",tag,"audience","all","thresholdAmount",0,"discountAmount",1,"validFrom","2026-01-01 00:00:00","validTo","2030-12-31 23:59:59","status","ACTIVE"));coupons.add(c.path("id").asLong());return c;
    }

    @Test void couponEditsAndDeleteRejectStaleOrMissingVersionWithoutLostUpdates() throws Exception
    {
        JsonNode c=coupon();long id=c.path("id").asLong();Map<String,Object> first=body(c),stale=body(c);
        for(Map<String,Object> form:Arrays.asList(first,stale)){form.put("validFrom","2026-01-01 00:00:00");form.put("validTo","2030-12-31 23:59:59");}
        first.put("status","INACTIVE");first.put("discountAmount",new BigDecimal("0.50"));
        JsonNode updated=a(HttpMethod.PUT,"/coupons/"+id,first);assertEquals(c.path("version").asInt()+1,updated.path("version").asInt());
        stale.put("name","must-not-overwrite");assertEquals(409,call(HttpMethod.PUT,"/qicai/admin/coupons/"+id,stale,admin,true).getStatusCodeValue());
        assertEquals("INACTIVE",jdbc.queryForObject("SELECT status FROM qc_coupon WHERE id=?",String.class,id));assertEquals(new BigDecimal("0.50"),jdbc.queryForObject("SELECT discount_amount FROM qc_coupon WHERE id=?",BigDecimal.class,id));
        stale.remove("version");assertEquals(400,call(HttpMethod.PUT,"/qicai/admin/coupons/"+id,stale,admin,true).getStatusCodeValue());
        assertEquals(400,call(HttpMethod.DELETE,"/qicai/admin/coupons/"+id,null,admin,true).getStatusCodeValue());assertEquals(409,call(HttpMethod.DELETE,"/qicai/admin/coupons/"+id+"?version=0",null,admin,true).getStatusCodeValue());
        a(HttpMethod.DELETE,"/coupons/"+id+"?version="+updated.path("version").asInt(),null);assertEquals(1,jdbc.queryForObject("SELECT deleted FROM qc_coupon WHERE id=?",Integer.class,id));
        JsonNode audit=json.readTree(jdbc.queryForObject("SELECT after_values FROM qc_business_audit WHERE object_type='coupon' AND object_id=? AND action='UPDATE_COUPON'",String.class,id));assertEquals("INACTIVE",audit.path("status").asText());assertEquals(0,audit.path("discountAmount").decimalValue().compareTo(new BigDecimal("0.50")));
    }

    @Test void administratorAuditUsesTrustedActorAndRollsBackBusinessIfAuditWriteFails() throws Exception
    {
        Map<String,Object> p=body(product(product));p.put("actorId",99999999);p.put("actorName","forged-secret-actor");p.put("stock",777);
        a(HttpMethod.PUT,"/products/"+product,p);
        Map<String,Object> audit=jdbc.queryForMap("SELECT actor_type,actor_id,actor_name,before_values,after_values FROM qc_business_audit WHERE object_type='product' AND object_id=? AND action='UPDATE_PRODUCT' ORDER BY id DESC LIMIT 1",product);
        assertEquals("admin",audit.get("actor_type"));assertEquals(1,((Number)audit.get("actor_id")).longValue());assertEquals("admin",audit.get("actor_name"));assertFalse(audit.toString().contains("forged-secret-actor"));
        assertEquals(1000,json.readTree(String.valueOf(audit.get("before_values"))).path("stock").asLong());assertEquals(777,json.readTree(String.valueOf(audit.get("after_values"))).path("stock").asLong());
        assertEquals(401,call(HttpMethod.GET,"/qicai/admin/audit",null,consumer,false).getStatusCodeValue());assertTrue(a(HttpMethod.GET,"/audit?objectType=product&objectId="+product,null).path("rows").size()>=2);
        long count=auditCount();Map<String,Object> before=jdbc.queryForMap("SELECT stock,version FROM qc_product WHERE id=?",product);p=body(product(product));p.put("stock",555);
        String trigger="rem_audit_fail_"+product;jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON qc_business_audit FOR EACH ROW BEGIN IF NEW.object_type='product' AND NEW.object_id="+product+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic audit failure'; END IF; END");
        try{assertTrue(call(HttpMethod.PUT,"/qicai/admin/products/"+product,p,admin,true).getStatusCodeValue()>=400);assertEquals(before,jdbc.queryForMap("SELECT stock,version FROM qc_product WHERE id=?",product));assertEquals(count,auditCount());}
        finally{jdbc.execute("DROP TRIGGER "+trigger);}
        JsonNode c=coupon();a(HttpMethod.POST,"/coupons/"+c.path("id").asLong()+"/grant/"+customer,map());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_business_audit WHERE action='GRANT_COUPON' AND object_id=(SELECT id FROM qc_customer_coupon WHERE coupon_id=? AND customer_id=?)",Integer.class,c.path("id").asLong(),customer));
        for(String value:jdbc.queryForList("SELECT CONCAT(COALESCE(before_values,''),COALESCE(after_values,'')) FROM qc_business_audit WHERE (object_type='courier' AND object_id=?) OR (object_type='customer' AND object_id=?)",String.class,courier,customer))
            for(String secret:Arrays.asList(password,"password_hash","openid","contact_phone","address"))assertFalse(value.contains(secret));
        a(HttpMethod.PUT,"/customers/"+otherCustomer+"/review",map("status","DISABLED","version",0));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_business_audit WHERE object_type='customer' AND object_id=? AND action='REVIEW_CUSTOMER' AND actor_id=1",Integer.class,otherCustomer));
        Map<String,Object> driverForm=jdbc.queryForMap("SELECT phone,name,vehicle_no AS vehicleNo,status,version FROM qc_courier WHERE id=?",courier);driverForm.put("available",false);
        a(HttpMethod.PUT,"/couriers/"+courier,driverForm);a(HttpMethod.DELETE,"/couriers/"+courier,null);
        assertEquals(new HashSet<>(Arrays.asList("CREATE_COURIER","UPDATE_COURIER","DELETE_COURIER")),new HashSet<>(jdbc.queryForList("SELECT action FROM qc_business_audit WHERE object_type='courier' AND object_id=?",String.class,courier)));
        a(HttpMethod.DELETE,"/products/"+product,null);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_business_audit WHERE object_type='product' AND object_id=? AND action='DELETE_PRODUCT'",Integer.class,product));
    }

    @Test void catalogPaginationReachesEveryProductAndKeepsPricesRoleScoped()
    {
        Set<Long> expected=new HashSet<>();String category=tag+"-BULK";
        for(int i=0;i<105;i++){String sku=tag+"-"+i;jdbc.update("INSERT INTO qc_product(sku,name,category,unit,stock,business_price,consumer_price,status) VALUES(?,?,?,'item',1,1,2,'ACTIVE')",sku,sku,category);long id=jdbc.queryForObject("SELECT id FROM qc_product WHERE sku=?",Long.class,sku);products.add(id);expected.add(id);}
        Set<Long> actual=new HashSet<>();
        for(int page=1;page<=6;page++)
        {
            JsonNode list=m(HttpMethod.GET,"/catalog/products?category="+category+"&pageNum="+page+"&pageSize=20",null,null);assertEquals(105,list.path("total").asInt());assertEquals(page,list.path("pageNum").asInt());assertEquals(20,list.path("pageSize").asInt());
            for(JsonNode row:list.path("rows")){assertTrue(actual.add(row.path("id").asLong()));assertEquals(2,row.path("price").asInt());assertFalse(row.has("businessPrice"));}
        }
        assertEquals(expected,actual);assertEquals(0,m(HttpMethod.GET,"/catalog/products?category="+category+"&pageNum=7&pageSize=20",null,null).path("rows").size());
        assertEquals(400,call(HttpMethod.GET,"/qicai/mobile/catalog/products?category="+category,null,null,false).getStatusCodeValue());
        for(String invalid:Arrays.asList("pageNum=0","pageNum=-1","pageSize=0","pageSize=101","pageNum=x"))assertEquals(400,call(HttpMethod.GET,"/qicai/mobile/catalog/products?"+invalid,null,null,false).getStatusCodeValue());
        JsonNode enterprise=m(HttpMethod.GET,"/products?category="+category+"&pageNum=1&pageSize=100",null,business);assertEquals(105,enterprise.path("total").asInt());assertEquals(1,enterprise.path("rows").get(0).path("price").asInt());
        assertEquals(403,call(HttpMethod.GET,"/qicai/mobile/products?pageNum=1&pageSize=20",null,pending,false).getStatusCodeValue());
    }

    @Test void courierPaginationStatisticsCoverAllOwnTasksBeyondCurrentPage()
    {
        String foreignPhone="178"+String.format(Locale.ROOT,"%08d",Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),99999999L));
        long foreignCourier=a(HttpMethod.POST,"/couriers",map("phone",foreignPhone,"password",password,"name",tag+"-foreign","status","ACTIVE","available",true)).path("id").asLong();couriers.add(foreignCourier);
        String foreignNo=tag+"FOREIGN";jdbc.update("INSERT INTO qc_order(order_no,customer_id,audience,status,subtotal_amount,discount_amount,payable_amount,contact_name,contact_phone,delivery_address,idempotency_key) VALUES(?,?,'consumer','ASSIGNED',2,0,2,'foreign','13900000000','foreign-address',?)",foreignNo,otherCustomer,foreignNo);
        long foreignOrder=jdbc.queryForObject("SELECT id FROM qc_order WHERE order_no=?",Long.class,foreignNo);orders.add(foreignOrder);jdbc.update("INSERT INTO qc_delivery_task(task_no,order_id,courier_id,status) VALUES(?,?,?,'ASSIGNED')",foreignNo,foreignOrder,foreignCourier);
        String[] states={"ASSIGNED","PICKED_UP","IN_DELIVERY","EXCEPTION","DELIVERED"};
        for(int i=0;i<105;i++)
        {
            String no=tag+"T"+i;jdbc.update("INSERT INTO qc_order(order_no,customer_id,audience,status,subtotal_amount,discount_amount,payable_amount,contact_name,contact_phone,delivery_address,idempotency_key) VALUES(?,?,'consumer','ASSIGNED',2,0,2,'synthetic','13900000000','isolated',?)",no,customer,no);long id=jdbc.queryForObject("SELECT id FROM qc_order WHERE order_no=?",Long.class,no);orders.add(id);
            jdbc.update("INSERT INTO qc_delivery_task(task_no,order_id,courier_id,status) VALUES(?,?,?,?)",no,id,courier,states[i%5]);
        }
        Set<Long> tasks=new HashSet<>();
        for(int page=1;page<=6;page++)
        {
            JsonNode result=m(HttpMethod.GET,"/courier/tasks?pageNum="+page+"&pageSize=20",null,driver);assertEquals(105,result.path("total").asInt());assertEquals(105,result.path("summary").path("total").asInt());
            for(String key:Arrays.asList("assigned","pickedUp","inDelivery","exception","delivered"))assertEquals(21,result.path("summary").path(key).asInt());
            for(JsonNode row:result.path("rows")){assertEquals(courier,row.path("courierId").asLong());assertTrue(tasks.add(row.path("id").asLong()));}
        }
        assertEquals(105,tasks.size());JsonNode filtered=m(HttpMethod.GET,"/courier/tasks?status=ASSIGNED&pageNum=1&pageSize=10",null,driver);assertEquals(21,filtered.path("total").asInt());assertEquals(105,filtered.path("summary").path("total").asInt());
        assertEquals(400,call(HttpMethod.GET,"/qicai/mobile/courier/tasks",null,driver,false).getStatusCodeValue());
    }

    Map<String,Object> point(){return map("deliveryLatitude",new BigDecimal("22.5430961"),"deliveryLongitude",new BigDecimal("114.0578652"),"deliveryLocationName","synthetic-place");}
    @Test void orderLocationIsValidatedPersistedIdempotentAndSharedWithCourier()
    {
        Map<String,Object> location=point();String key=UUID.randomUUID().toString();location.put("idempotencyKey",key);JsonNode created=order(location,consumer);long id=created.path("id").asLong();
        assertEquals(new BigDecimal("22.5430961"),jdbc.queryForObject("SELECT delivery_latitude FROM qc_order WHERE id=?",BigDecimal.class,id));assertEquals("GCJ-02",created.path("coordinateSystem").asText());
        Map<String,Object> retry=point();retry.put("idempotencyKey",key);retry.put("deliveryLatitude",new BigDecimal("1.0000000"));JsonNode duplicate=order(retry,consumer);assertEquals(id,duplicate.path("id").asLong());assertEquals(0,created.path("deliveryLatitude").decimalValue().compareTo(duplicate.path("deliveryLatitude").decimalValue()));
        long task=assign(id);JsonNode courierView=m(HttpMethod.GET,"/courier/tasks/"+task,null,driver);assertEquals(created.path("deliveryLatitude"),courierView.path("deliveryLatitude"));assertEquals(created.path("deliveryLongitude"),courierView.path("deliveryLongitude"));assertEquals("synthetic-place",courierView.path("deliveryLocationName").asText());
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM qc_order WHERE customer_id=?",Long.class,customer),stock=stock();
        List<Map<String,Object>> invalid=new ArrayList<>();invalid.add(map("deliveryLatitude",1));
        for(Object bad:Arrays.asList("22.1",new BigDecimal("22.12345678"),91,-91)){Map<String,Object> value=point();value.put("deliveryLatitude",bad);invalid.add(value);}
        Map<String,Object> longitude=point();longitude.put("deliveryLongitude",181);invalid.add(longitude);Map<String,Object> name=point();name.put("deliveryLocationName",text(121));invalid.add(name);Map<String,Object> wrongSystem=point();wrongSystem.put("coordinateSystem","WGS84");invalid.add(wrongSystem);
        for(Map<String,Object> value:invalid){value.putAll(map("productId",product,"quantity",1,"idempotencyKey",UUID.randomUUID().toString()));assertEquals(400,call(HttpMethod.POST,"/qicai/mobile/orders",value,consumer,false).getStatusCodeValue());}
        assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM qc_order WHERE customer_id=?",Long.class,customer));assertEquals(stock,stock());
    }

    @Test void onlyOwnerCanFillAnEmptyLocationOnceWithCurrentVersionAndAllowedState()
    {
        JsonNode legacy=order(map(),consumer);long id=legacy.path("id").asLong();assertTrue(legacy.path("deliveryLatitude").isNull());Map<String,Object> location=point();location.put("version",legacy.path("version").asInt());location.put("deliveryAddress","must-not-rewrite");
        assertEquals(404,call(HttpMethod.PUT,"/qicai/mobile/orders/"+id+"/location",location,otherConsumer,false).getStatusCodeValue());assertEquals(403,call(HttpMethod.PUT,"/qicai/mobile/orders/"+id+"/location",location,driver,false).getStatusCodeValue());
        location.put("version",99);assertEquals(409,call(HttpMethod.PUT,"/qicai/mobile/orders/"+id+"/location",location,consumer,false).getStatusCodeValue());location.put("version",legacy.path("version").asInt());
        JsonNode filled=m(HttpMethod.PUT,"/orders/"+id+"/location",location,consumer);assertEquals("original-address",filled.path("deliveryAddress").asText());assertEquals(legacy.path("version").asInt()+1,filled.path("version").asInt());assertTrue(filled.path("items").isArray());
        location.put("version",filled.path("version").asInt());assertEquals(409,call(HttpMethod.PUT,"/qicai/mobile/orders/"+id+"/location",location,consumer,false).getStatusCodeValue());
        assertEquals(filled.path("deliveryLatitude"),m(HttpMethod.GET,"/orders/"+id,null,login(customer,"consumer")).path("deliveryLatitude"));
        for(String state:Arrays.asList("COMPLETED","CANCELLED","REFUND_REVIEW","REFUNDING","REFUNDED"))
        {
            JsonNode order=order(map(),consumer);long blocked=order.path("id").asLong();if(state.startsWith("REFUND"))jdbc.update("UPDATE qc_order SET payment_status=? WHERE id=?",state,blocked);else jdbc.update("UPDATE qc_order SET status=? WHERE id=?",state,blocked);
            Map<String,Object> p=point();p.put("version",order.path("version").asInt());assertEquals(409,call(HttpMethod.PUT,"/qicai/mobile/orders/"+blocked+"/location",p,consumer,false).getStatusCodeValue());assertNull(jdbc.queryForObject("SELECT delivery_latitude FROM qc_order WHERE id=?",BigDecimal.class,blocked));
        }
    }

    @Test void rawJsonCoordinatesRejectHiddenPrecisionAndRangeBeforeAnyBusinessWrite() throws Exception
    {
        JsonNode legacy=order(map(),consumer);long id=legacy.path("id").asLong();
        Map<String,Object> before=jdbc.queryForMap("SELECT delivery_latitude,delivery_longitude,delivery_location_name,version FROM qc_order WHERE id=?",id);
        long beforeStock=stock();int beforeOrders=jdbc.queryForObject("SELECT COUNT(*) FROM qc_order WHERE customer_id=?",Integer.class,customer);
        for(String[] coordinates:Arrays.asList(new String[]{"22.543096100000001","114.0578652"},new String[]{"90.000000000000001","114.0578652"},new String[]{"22.5430961","180.000000000000001"},new String[]{"-90.000000000000001","114.0578652"},new String[]{"22.5430961","-180.000000000000001"},new String[]{"1e10000","1"},new String[]{"-1e10000","1"},new String[]{"1e-10000","1"},new String[]{"-1e-10000","1"}))
        {
            String pointJson="\"deliveryLatitude\":"+coordinates[0]+",\"deliveryLongitude\":"+coordinates[1]+",\"deliveryLocationName\":\"synthetic-point\"";
            String createJson="{\"productId\":"+product+",\"quantity\":1,\"idempotencyKey\":\""+UUID.randomUUID()+"\",\"deliveryAddress\":\"synthetic-address\","+pointJson+"}";
            ResponseEntity<JsonNode> created=call(HttpMethod.POST,"/qicai/mobile/orders",createJson,consumer,false);
            if(created.getStatusCodeValue()==201)orders.add(created.getBody().path("data").path("id").asLong());
            assertEquals(400,created.getStatusCodeValue(),Arrays.toString(coordinates));
            String fillJson="{\"version\":"+legacy.path("version").asInt()+","+pointJson+"}";
            assertEquals(400,call(HttpMethod.PUT,"/qicai/mobile/orders/"+id+"/location",fillJson,consumer,false).getStatusCodeValue(),Arrays.toString(coordinates));
            assertEquals(before,jdbc.queryForMap("SELECT delivery_latitude,delivery_longitude,delivery_location_name,version FROM qc_order WHERE id=?",id));
            assertEquals(beforeStock,stock());assertEquals(beforeOrders,jdbc.queryForObject("SELECT COUNT(*) FROM qc_order WHERE customer_id=?",Integer.class,customer));
        }
        String valid="{\"version\":"+legacy.path("version").asInt()+",\"deliveryLatitude\":90.0000000,\"deliveryLongitude\":180.0000000,\"deliveryLocationName\":\"synthetic-boundary\"}";
        ok(call(HttpMethod.PUT,"/qicai/mobile/orders/"+id+"/location",valid,consumer,false),200);
        assertEquals(new BigDecimal("90.0000000"),jdbc.queryForObject("SELECT delivery_latitude FROM qc_order WHERE id=?",BigDecimal.class,id));
        assertEquals(new BigDecimal("180.0000000"),jdbc.queryForObject("SELECT delivery_longitude FROM qc_order WHERE id=?",BigDecimal.class,id));
        assertEquals(beforeStock,stock());
        String zeroJson="{\"productId\":"+product+",\"quantity\":1,\"idempotencyKey\":\""+UUID.randomUUID()+"\",\"deliveryAddress\":\"synthetic-address\",\"deliveryLatitude\":0e10000,\"deliveryLongitude\":0e-10000,\"deliveryLocationName\":\"synthetic-zero\"}";
        JsonNode zero=ok(call(HttpMethod.POST,"/qicai/mobile/orders",zeroJson,consumer,false),201);long zeroId=zero.path("id").asLong();orders.add(zeroId);
        assertEquals(new BigDecimal("0.0000000"),jdbc.queryForObject("SELECT delivery_latitude FROM qc_order WHERE id=?",BigDecimal.class,zeroId));
        assertEquals(new BigDecimal("0.0000000"),jdbc.queryForObject("SELECT delivery_longitude FROM qc_order WHERE id=?",BigDecimal.class,zeroId));assertEquals(beforeStock-1,stock());
    }

    @Test void readinessRejectsReachableDatabaseWithOnlyOldUploadTable() throws Exception
    {
        JsonNode health=m(HttpMethod.GET,"/health",null,null);assertEquals("UP",health.path("status").asText());assertEquals("UP",health.path("schema").asText());
        String schema="qicai_ready_"+UUID.randomUUID().toString().replace("-","").substring(0,12);assertTrue(schema.matches("qicai_ready_[a-f0-9]{12}"));jdbc.execute("CREATE DATABASE "+schema);
        try
        {
            DriverManagerDataSource source=new DriverManagerDataSource(System.getenv("QICAI_DB_URL").replace("/qicai_acceptance?","/"+schema+"?"),System.getenv("QICAI_DB_USERNAME"),System.getenv("QICAI_DB_PASSWORD"));JdbcTemplate isolated=new JdbcTemplate(source);isolated.execute("CREATE TABLE qc_upload(file_name VARCHAR(500))");
            Map<String,Object> result=new QicaiHealthService(isolated,redis).readiness();assertEquals("UP",result.get("database"));assertEquals("UP",result.get("redis"));assertEquals("DOWN",result.get("schema"));assertEquals("DOWN",result.get("status"));assertEquals(new HashSet<>(Arrays.asList("service","status","database","schema","redis")),result.keySet());
        }
        finally{jdbc.execute("DROP DATABASE "+schema);}
    }
}

package com.ruoyi.qicai;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.qicai.payment.WechatPayProperties;
import com.ruoyi.qicai.payment.WechatPaymentGateway;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real HTTP/MySQL/Redis transactions; only the external WeChat exchange is mocked. */
@SpringBootTest(classes=RuoYiApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"server.address=127.0.0.1", "qicai.wechat-login.enabled=true", "qicai.wechat-pay.enabled=false",
                "qicai.wechat-pay.maintenance-enabled=false", "qicai.wechat-pay.app-id=wx1234567890abcdef",
                "qicai.sms.dev-mode=false", "spring.redis.host=127.0.0.1", "spring.redis.port=16399",
                "spring.devtools.restart.enabled=false"})
@ActiveProfiles("qicai")
@ContextConfiguration(initializers=QicaiCatalogIT.IsolatedEnvironment.class)
class QicaiStorefrontIT
{
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired StringRedisTemplate redis;
    @Autowired WechatPayProperties config;
    @Autowired QicaiWechatAuthService wechat;
    @Autowired QicaiWechatCodeService codes;
    @Autowired BCryptPasswordEncoder passwords;
    @MockBean WechatPaymentGateway provider;
    String tag;
    long product;
    final Set<String> usedCodes = ConcurrentHashMap.newKeySet();
    final List<Long> legacyCustomers = new ArrayList<Long>();

    static Map<String,Object> map(Object... values)
    {
        Map<String,Object> r=new LinkedHashMap<String,Object>();
        for(int i=0;i<values.length;i+=2) r.put((String)values[i],values[i+1]); return r;
    }
    String code(String identity) { String c=tag+"-"+identity+"-"+UUID.randomUUID(); usedCodes.add(c); return c; }
    String ipKey() { return "qc:wechat:ip:"+QicaiAuthService.sha256("127.0.0.1"); }
    String codeKey(String c) { return "qc:wechat:code:"+config.getAppId()+":"+QicaiAuthService.sha256(c); }

    @BeforeEach void fixtures()
    {
        assertEquals(13319,jdbc.queryForObject("SELECT @@port",Integer.class));
        assertEquals("qicai_acceptance",jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(Paths.get(System.getenv("QICAI_RUNTIME"),"mysql").toAbsolutePath().normalize(),Paths.get(jdbc.queryForObject("SELECT @@datadir",String.class)).toAbsolutePath().normalize());
        tag="WXIT"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        config.setEnabled(false); config.setAppId("wx1234567890abcdef");
        ReflectionTestUtils.setField(wechat,"enabled",true);
        redis.delete(ipKey());
        jdbc.update("INSERT INTO qc_product(sku,name,category,unit,stock,business_price,consumer_price,status) VALUES(?,?,?,'件',100,13.37,24.68,'ACTIVE')",tag,tag,tag);
        product=jdbc.queryForObject("SELECT id FROM qc_product WHERE sku=?",Long.class,tag);
        when(provider.exchangeCode(anyString())).thenAnswer(inv->{
            String c=inv.getArgument(0); return c.contains("-other-")?tag+"_other":tag+"_owner";
        });
    }

    @AfterEach void cleanup()
    {
        config.setEnabled(false); ReflectionTestUtils.setField(wechat,"enabled",true);
        // Own fixtures only; never clear another account's state or flush Redis.
        List<Long> ids=jdbc.queryForList("SELECT customer_id FROM qc_wechat_identity WHERE app_id=? AND open_id LIKE ?",Long.class,config.getAppId(),tag+"%");
        ids.addAll(legacyCustomers);
        jdbc.update("DELETE FROM qc_wechat_account WHERE app_id=? AND open_id LIKE ?",config.getAppId(),tag+"%");
        for(long id:new HashSet<Long>(ids))
        {
            jdbc.update("DELETE i FROM qc_order_item i JOIN qc_order o ON o.id=i.order_id WHERE o.customer_id=?",id);
            jdbc.update("DELETE FROM qc_order WHERE customer_id=?",id);
            jdbc.update("DELETE FROM qc_session WHERE principal_type='customer' AND principal_id=?",id);
            jdbc.update("DELETE FROM qc_wechat_identity WHERE customer_id=?",id);
            jdbc.update("DELETE FROM qc_customer_sales WHERE customer_id=?",id);
            jdbc.update("DELETE FROM qc_customer WHERE id=?",id);
        }
        jdbc.update("DELETE FROM qc_product WHERE id=?",product);
        for(String c:usedCodes) redis.delete(codeKey(c));
        redis.delete(ipKey());
    }

    ResponseEntity<JsonNode> call(HttpMethod method,String path,Object body,String token)
    {
        HttpHeaders h=new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Qicai-Privacy-Accepted","1");h.set("X-Qicai-Privacy-Version",QicaiPrivacyService.VERSION);
        if(token!=null)h.set("X-Qicai-Token",token);
        return http.exchange("/qicai/mobile"+path,method,new HttpEntity<Object>(body,h),JsonNode.class);
    }
    JsonNode login(String identity,String audience)
    {
        ResponseEntity<JsonNode> r=call(HttpMethod.POST,"/auth/wechat/login",map("code",code(identity),"audience",audience),null);
        assertEquals(200,r.getStatusCodeValue(),r.getBody().toString());return r.getBody().path("data");
    }
    String token(JsonNode data) { return data.path("token").asText(); }
    long id(JsonNode data) { return data.path("profile").path("id").asLong(); }
    Map<String,Object> business(String c) { return map("code",c,"companyName",tag,"contactName","申请联系人","phone","13900008888","taxId",tag.substring(0,18).toUpperCase(Locale.ROOT),"address","企业测试收货地址"); }
    Map<String,Object> order(String key) { return map("productId",product,"quantity",2,"idempotencyKey",key,"contactName","收件人甲","contactPhone","13900008888","deliveryAddress","测试地址甲"); }
    int stock() { return jdbc.queryForObject("SELECT stock FROM qc_product WHERE id=?",Integer.class,product); }
    Map<String,Object> customer(long id) { return jdbc.queryForMap("SELECT audience,phone,password_hash,auth_method,contact_phone,contact_name,address,status,version FROM qc_customer WHERE id=?",id); }
    int orders(long id) { return jdbc.queryForObject("SELECT COUNT(*) FROM qc_order WHERE customer_id=?",Integer.class,id); }

    @Test void wechatBusinessInvitationBindsOnlyActiveSalesAndRollsBackInvalidRegistration()
    {
        jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,status,del_flag) VALUES(?,?,?,'0','0')",tag,"isolated sales",passwords.encode("SyntheticOnly2026!"));
        long owner=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        long role=jdbc.queryForObject("SELECT role_id FROM sys_role WHERE role_key='qicai_sales'",Long.class);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",owner,role);
        String invitation=UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO qc_sales_profile(user_id,invite_code) VALUES(?,?)",owner,invitation);
        Map<String,Object> body=business(code("invited"));body.put("salesInviteCode",invitation);
        ResponseEntity<JsonNode> registration=call(HttpMethod.POST,"/auth/wechat/business-register",body,null);
        assertEquals(201,registration.getStatusCodeValue(),registration.getBody().toString());
        long customer=id(registration.getBody().path("data"));
        assertEquals("PENDING",registration.getBody().path("data").path("profile").path("status").asText());
        assertEquals(owner,jdbc.queryForObject("SELECT sales_user_id FROM qc_customer_sales WHERE customer_id=?",Long.class,customer));
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM qc_customer",Integer.class);
        jdbc.update("UPDATE sys_user SET status='1' WHERE user_id=?",owner);
        body=business(code("other"));body.put("salesInviteCode",invitation);body.put("taxId",tag.substring(0,17).toUpperCase(Locale.ROOT)+"Z");
        assertEquals(400,call(HttpMethod.POST,"/auth/wechat/business-register",body,null).getStatusCodeValue());
        assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM qc_customer",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_customer_sales WHERE sales_user_id=?",Integer.class,owner));
    }

    @Test void confirmedQuoteRejectsStalePricesBeforeSavingOrderOrReservingStock()
    {
        JsonNode user=login("owner","consumer"); config.setEnabled(true); clearInvocations(provider);
        Map<String,Object> body=order("quote-"+tag); body.put("expectedUnitPrice","24.68"); body.put("expectedPayableAmount","49.36");
        jdbc.update("UPDATE qc_product SET consumer_price=25.00,version=version+1 WHERE id=?",product);
        assertEquals(409,call(HttpMethod.POST,"/orders",body,token(user)).getStatusCodeValue());
        assertEquals(100,stock()); assertEquals(0,orders(id(user)));
        body.put("expectedUnitPrice","25.00"); body.put("expectedPayableAmount","49.99");
        assertEquals(409,call(HttpMethod.POST,"/orders",body,token(user)).getStatusCodeValue());
        assertEquals(100,stock()); assertEquals(0,orders(id(user)));
        body.put("expectedPayableAmount","50.00");
        ResponseEntity<JsonNode> created=call(HttpMethod.POST,"/orders",body,token(user)); assertEquals(201,created.getStatusCodeValue());
        JsonNode saved=created.getBody().path("data"); assertEquals("UNPAID",saved.path("paymentStatus").asText()); assertEquals("PENDING_PAYMENT",saved.path("status").asText());
        assertEquals(0,new java.math.BigDecimal("50.00").compareTo(saved.path("payableAmount").decimalValue())); assertEquals(98,stock());
        // A retry returns its original snapshot, even after another price change.
        jdbc.update("UPDATE qc_product SET consumer_price=30.00,version=version+1 WHERE id=?",product);
        JsonNode retry=call(HttpMethod.POST,"/orders",body,token(user)).getBody().path("data");
        assertEquals(saved.path("id"),retry.path("id")); assertEquals(saved.path("payableAmount"),retry.path("payableAmount"));
        assertEquals(1,orders(id(user))); assertEquals(98,stock()); verifyNoInteractions(provider);
    }

    @Test void submittedOrderLookupIsReadOnlyAndScopedToCurrentCustomer()
    {
        JsonNode owner=login("owner","consumer"),other=login("other","consumer"); config.setEnabled(true); clearInvocations(provider);
        String key="lookup-"+tag; JsonNode saved=call(HttpMethod.POST,"/orders",order(key),token(owner)).getBody().path("data");
        assertEquals(401,call(HttpMethod.GET,"/orders/submission?idempotencyKey="+key,null,null).getStatusCodeValue());
        JsonNode recovered=call(HttpMethod.GET,"/orders/submission?idempotencyKey="+key,null,token(owner)).getBody().path("data");
        assertEquals(saved.path("id"),recovered.path("id")); assertEquals(saved.path("items"),recovered.path("items"));
        JsonNode hidden=call(HttpMethod.GET,"/orders/submission?idempotencyKey="+key,null,token(other)).getBody().path("data");
        assertTrue(hidden.isNull()||hidden.isMissingNode());
        JsonNode absent=call(HttpMethod.GET,"/orders/submission?idempotencyKey=never-"+tag,null,token(owner)).getBody().path("data");
        assertTrue(absent.isNull()||absent.isMissingNode());
        assertEquals(1,orders(id(owner))); assertEquals(0,orders(id(other))); assertEquals(98,stock()); verifyNoInteractions(provider);
    }

    @Test void confirmationFieldsMustBeExactMoneyAndCannotOverrideAuthoritativePrice()
    {
        JsonNode user=login("owner","consumer");
        for(Object value:Arrays.asList("NaN","1e2","-1.00","24.681",null,new HashMap<String,Object>()))
        {
            Map<String,Object> body=order("invalid-"+tag); body.put("expectedUnitPrice",value); body.put("expectedPayableAmount","49.36");
            assertEquals(400,call(HttpMethod.POST,"/orders",body,token(user)).getStatusCodeValue());
        }
        Map<String,Object> body=order("invalid-"+tag); body.put("expectedUnitPrice","0.01"); body.put("expectedPayableAmount","0.02");
        assertEquals(409,call(HttpMethod.POST,"/orders",body,token(user)).getStatusCodeValue());
        body.remove("expectedPayableAmount"); assertEquals(400,call(HttpMethod.POST,"/orders",body,token(user)).getStatusCodeValue());
        assertEquals(100,stock()); assertEquals(0,orders(id(user)));
    }

    @Test void consumerFirstLoginCreatesNoPasswordOrFakeContactAndReusesTrustedIdentity()
    {
        JsonNode a=login("owner","consumer"),b=login("owner","consumer"); assertEquals(id(a),id(b));
        assertNotEquals(token(a),token(b)); assertTrue(a.path("expiresIn").asInt()>0);
        Map<String,Object> row=customer(id(a));
        for(String field:Arrays.asList("phone","password_hash","contact_phone","contact_name","address")) assertNull(row.get(field),field);
        assertEquals("WECHAT",row.get("auth_method")); assertEquals("ACTIVE",row.get("status"));
        assertTrue(a.path("profile").path("phone").isNull());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_wechat_account WHERE app_id=? AND open_id=? AND consumer_customer_id=?",Integer.class,config.getAppId(),tag+"_owner",id(a)));
        assertEquals(tag+"_owner",jdbc.queryForObject("SELECT open_id FROM qc_wechat_identity WHERE customer_id=?",String.class,id(a)));
        assertEquals(503,call(HttpMethod.POST,"/payments/wechat/identity",map("code",code("owner")),token(a)).getStatusCodeValue());
        assertEquals(401,call(HttpMethod.POST,"/auth/login",map("audience","consumer","phone","13900008888","password","any-password"),null).getStatusCodeValue());
    }

    @Test void businessIsSeparatePendingAccountAndCannotSeeEnterprisePriceOrOrderBeforeApproval()
    {
        JsonNode c=login("owner","consumer");
        assertEquals(404,call(HttpMethod.POST,"/auth/wechat/login",map("code",code("owner"),"audience","business"),null).getStatusCodeValue());
        ResponseEntity<JsonNode> registration=call(HttpMethod.POST,"/auth/wechat/business-register",business(code("owner")),null);
        assertEquals(201,registration.getStatusCodeValue()); JsonNode b=registration.getBody().path("data");
        assertNotEquals(id(c),id(b)); assertEquals("PENDING",b.path("profile").path("status").asText());
        assertNull(customer(id(b)).get("phone")); assertNull(customer(id(b)).get("password_hash"));
        assertEquals("13900008888",b.path("profile").path("phone").asText());
        assertEquals(id(b),id(login("owner","business")));
        for(String path:Arrays.asList("/products","/products/"+product)) assertEquals(403,call(HttpMethod.GET,path,null,token(b)).getStatusCodeValue());
        assertEquals(403,call(HttpMethod.POST,"/orders",order(tag),token(b)).getStatusCodeValue()); assertEquals(100,stock());assertEquals(0,orders(id(b)));
        JsonNode publicProduct=call(HttpMethod.GET,"/catalog/products/"+product+"?audience=business",null,token(b)).getBody().path("data");
        assertEquals(9,publicProduct.size()); assertEquals("24.68",publicProduct.path("price").asText());assertEquals("consumer",publicProduct.path("audience").asText());assertFalse(publicProduct.has("businessPrice"));
        jdbc.update("UPDATE qc_customer SET status='ACTIVE' WHERE id=?",id(b));
        assertEquals("13.37",call(HttpMethod.GET,"/products/"+product,null,token(b)).getBody().path("data").path("price").asText());
        Map<String,Object> forged=order(tag+"approved");forged.put("audience","consumer");forged.put("price",1);
        ResponseEntity<JsonNode> placed=call(HttpMethod.POST,"/orders",forged,token(b)); assertEquals(201,placed.getStatusCodeValue());
        assertEquals(0,new java.math.BigDecimal("26.74").compareTo(jdbc.queryForObject("SELECT payable_amount FROM qc_order WHERE customer_id=?",java.math.BigDecimal.class,id(b))));
        assertEquals(409,call(HttpMethod.POST,"/auth/wechat/business-register",business(code("owner")),null).getStatusCodeValue());
    }

    @Test void untrustedIdentityAndEscalationFieldsAreRejectedBeforeProviderOrDatabaseWrites()
    {
        for(String key:Arrays.asList("openid","openId","customerId","status","price","phone"))
        {
            Map<String,Object> body=map("code",code("owner"),"audience","consumer",key,"forged");
            assertEquals(400,call(HttpMethod.POST,"/auth/wechat/login",body,null).getStatusCodeValue());
        }
        for(String key:Arrays.asList("openid","customerId","status","price","audience"))
        {
            Map<String,Object> body=business(code("owner"));body.put(key,"ACTIVE");
            assertEquals(400,call(HttpMethod.POST,"/auth/wechat/business-register",body,null).getStatusCodeValue());
        }
        assertEquals(400,call(HttpMethod.POST,"/auth/wechat/login",map("code",map("nested","bad"),"audience","consumer"),null).getStatusCodeValue());
        verify(provider,never()).exchangeCode(anyString());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qc_wechat_account WHERE open_id LIKE ?",Integer.class,tag+"%"));
    }

    @Test void distinctWechatUsersWithSameReceiptPhoneRemainIsolatedAndSnapshotsSurviveLaterChanges()
    {
        JsonNode a=login("owner","consumer"),b=login("other","consumer"); assertNotEquals(id(a),id(b));
        ResponseEntity<JsonNode> first=call(HttpMethod.POST,"/orders",order(tag+"a"),token(a));assertEquals(201,first.getStatusCodeValue());
        long orderId=first.getBody().path("data").path("id").asLong();
        assertEquals(201,call(HttpMethod.POST,"/orders",order(tag+"b"),token(b)).getStatusCodeValue());
        assertEquals("13900008888",customer(id(a)).get("contact_phone"));assertEquals("13900008888",customer(id(b)).get("contact_phone"));
        assertNull(customer(id(a)).get("phone"));assertNull(customer(id(b)).get("phone"));
        assertEquals(404,call(HttpMethod.GET,"/orders/"+orderId,null,token(b)).getStatusCodeValue());
        Map<String,Object> next=order(tag+"new");next.put("contactName","收件人乙");next.put("contactPhone","13800007777");next.put("deliveryAddress","测试地址乙");
        assertEquals(201,call(HttpMethod.POST,"/orders",next,token(a)).getStatusCodeValue());
        assertEquals("测试地址乙",customer(id(a)).get("address"));
        Map<String,Object> saved=jdbc.queryForMap("SELECT contact_name,contact_phone,delivery_address,payable_amount FROM qc_order WHERE id=?",orderId);
        assertEquals("收件人甲",saved.get("contact_name"));assertEquals("13900008888",saved.get("contact_phone"));assertEquals("测试地址甲",saved.get("delivery_address"));
        assertEquals(0,new java.math.BigDecimal("49.36").compareTo((java.math.BigDecimal)saved.get("payable_amount")));
        assertEquals(200,call(HttpMethod.DELETE,"/auth/session",null,token(a)).getStatusCodeValue());
        assertEquals(401,call(HttpMethod.GET,"/orders/"+orderId,null,token(a)).getStatusCodeValue());
        assertEquals(200,call(HttpMethod.GET,"/orders/"+orderId,null,token(login("owner","consumer"))).getStatusCodeValue());
    }

    @Test void concurrentDifferentCodesForSameIdentityCreateOneCustomerAndOnePaymentIdentity() throws Exception
    {
        CyclicBarrier barrier=new CyclicBarrier(2);
        when(provider.exchangeCode(anyString())).thenAnswer(inv->{barrier.await(8,TimeUnit.SECONDS);return tag+"_owner";});
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try
        {
            Future<JsonNode>a=pool.submit(()->login("owner","consumer"));Future<JsonNode>b=pool.submit(()->login("owner","consumer"));
            assertEquals(id(a.get(15,TimeUnit.SECONDS)),id(b.get(15,TimeUnit.SECONDS)));
        }
        finally { pool.shutdownNow(); }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_wechat_account WHERE app_id=? AND open_id=?",Integer.class,config.getAppId(),tag+"_owner"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_wechat_identity WHERE app_id=? AND open_id=?",Integer.class,config.getAppId(),tag+"_owner"));
    }

    @Test void oneTimeCodeIsAtomicAcrossAudienceAndPaymentBindingAndHasExpiry() throws Exception
    {
        JsonNode owner=login("owner","consumer");reset(provider);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(provider.exchangeCode(anyString())).thenAnswer(inv->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return tag+"_owner";});
        String c=code("owner");ExecutorService pool=Executors.newSingleThreadExecutor();config.setEnabled(true);
        try
        {
            Future<ResponseEntity<JsonNode>> first=pool.submit(()->call(HttpMethod.POST,"/auth/wechat/login",map("code",c,"audience","consumer"),null));
            assertTrue(entered.await(8,TimeUnit.SECONDS));
            assertEquals(409,call(HttpMethod.POST,"/auth/wechat/login",map("code",c,"audience","business"),null).getStatusCodeValue());
            assertEquals(409,call(HttpMethod.POST,"/payments/wechat/identity",map("code",c),token(owner)).getStatusCodeValue());
            long ttl=redis.getExpire(codeKey(c),TimeUnit.SECONDS);assertTrue(ttl>0&&ttl<=300);
            release.countDown();assertEquals(200,first.get(12,TimeUnit.SECONDS).getStatusCodeValue());
        }
        finally { release.countDown();pool.shutdownNow();config.setEnabled(false); }
        verify(provider,times(1)).exchangeCode(c);
    }

    @Test void concurrentIdempotentOrdersKeepFirstReceiptAndDeductStockOnlyOnce() throws Exception
    {
        JsonNode account=login("owner","consumer");ExecutorService pool=Executors.newFixedThreadPool(2);CyclicBarrier barrier=new CyclicBarrier(2);
        List<Future<ResponseEntity<JsonNode>>> futures=new ArrayList<Future<ResponseEntity<JsonNode>>>();
        for(String name:Arrays.asList("收件人甲","收件人乙")) futures.add(pool.submit(()->{Map<String,Object>b=order(tag);b.put("contactName",name);barrier.await();return call(HttpMethod.POST,"/orders",b,token(account));}));
        try
        {
            ResponseEntity<JsonNode>a=futures.get(0).get(15,TimeUnit.SECONDS),b=futures.get(1).get(15,TimeUnit.SECONDS);
            assertEquals(201,a.getStatusCodeValue());assertEquals(201,b.getStatusCodeValue());assertEquals(a.getBody(),b.getBody());
        }
        finally { pool.shutdownNow(); }
        assertEquals(1,orders(id(account)));assertEquals(98,stock());assertEquals(1,((Number)customer(id(account)).get("version")).intValue());
        String persisted=jdbc.queryForObject("SELECT contact_name FROM qc_order WHERE customer_id=?",String.class,id(account));
        assertEquals(persisted,customer(id(account)).get("contact_name"));
        Map<String,Object> replay=order(tag);replay.put("contactName","不得覆盖");replay.put("contactPhone","bad");
        assertEquals(201,call(HttpMethod.POST,"/orders",replay,token(account)).getStatusCodeValue());
        assertEquals(persisted,customer(id(account)).get("contact_name"));assertEquals(98,stock());
    }

    @Test void invalidReceiptStockCouponAndLateDatabaseFailureLeaveNoPartialOrderOrProfile()
    {
        JsonNode account=login("owner","consumer");Map<String,Object> before=customer(id(account));
        Map<String,Object> missing=map("productId",product,"quantity",1,"idempotencyKey",tag);
        assertEquals(400,call(HttpMethod.POST,"/orders",missing,token(account)).getStatusCodeValue());
        for(Object invalid:Arrays.asList("", "123", Collections.singletonMap("nested","bad"), "139000088880"))
        {
            Map<String,Object> b=order(tag+UUID.randomUUID());b.put("contactPhone",invalid);
            assertEquals(400,call(HttpMethod.POST,"/orders",b,token(account)).getStatusCodeValue());
        }
        Map<String,Object> over=order(tag+"stock");over.put("quantity",101);assertEquals(409,call(HttpMethod.POST,"/orders",over,token(account)).getStatusCodeValue());
        Map<String,Object> coupon=order(tag+"coupon");coupon.put("customerCouponId",Long.MAX_VALUE);assertEquals(409,call(HttpMethod.POST,"/orders",coupon,token(account)).getStatusCodeValue());
        String trigger="sf_fail_"+tag.substring(4);
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON qc_order_item FOR EACH ROW BEGIN IF NEW.product_id="+product+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic storefront rollback'; END IF; END");
        try { assertEquals(500,call(HttpMethod.POST,"/orders",order(tag+"dbfail"),token(account)).getStatusCodeValue()); }
        finally { jdbc.execute("DROP TRIGGER "+trigger); }
        assertEquals(before,customer(id(account)));assertEquals(100,stock());assertEquals(0,orders(id(account)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qc_order_item WHERE product_id=?",Integer.class,product));
    }

    @Test void rejectedDisabledAndDeletedIdentityCannotRecreateOrKeepUsingOldSession()
    {
        JsonNode account=login("owner","consumer");
        for(String state:Arrays.asList("REJECTED","DISABLED","DELETED"))
        {
            jdbc.update("UPDATE qc_customer SET status=?,deleted=? WHERE id=?","DELETED".equals(state)?"ACTIVE":state,"DELETED".equals(state)?1:0,id(account));
            assertEquals(403,call(HttpMethod.POST,"/auth/wechat/login",map("code",code("owner"),"audience","consumer"),null).getStatusCodeValue());
            assertEquals(401,call(HttpMethod.GET,"/profile",null,token(account)).getStatusCodeValue());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_wechat_account WHERE open_id=?",Integer.class,tag+"_owner"));
        }
    }

    @Test void providerFailureRateLimitAndFeatureSwitchDoNotCreateCustomerOrSession()
    {
        ReflectionTestUtils.setField(wechat,"enabled",false);
        assertEquals(503,call(HttpMethod.POST,"/auth/wechat/login",map("code",code("owner"),"audience","consumer"),null).getStatusCodeValue());
        verify(provider,never()).exchangeCode(anyString());ReflectionTestUtils.setField(wechat,"enabled",true);
        redis.opsForValue().set(ipKey(),"60",60,TimeUnit.SECONDS);
        assertEquals(429,call(HttpMethod.POST,"/auth/wechat/login",map("code",code("owner"),"audience","consumer"),null).getStatusCodeValue());
        verify(provider,never()).exchangeCode(anyString());assertTrue(redis.getExpire(ipKey(),TimeUnit.SECONDS)>0);redis.delete(ipKey());
        String failed=code("owner");when(provider.exchangeCode(failed)).thenThrow(new QicaiApiException(HttpStatus.BAD_GATEWAY,"synthetic provider unavailable"));
        assertEquals(502,call(HttpMethod.POST,"/auth/wechat/login",map("code",failed,"audience","consumer"),null).getStatusCodeValue());
        assertEquals(409,call(HttpMethod.POST,"/auth/wechat/login",map("code",failed,"audience","consumer"),null).getStatusCodeValue());
        verify(provider,times(1)).exchangeCode(failed);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qc_wechat_account WHERE open_id LIKE ?",Integer.class,tag+"%"));
    }

    @Test void contactEditsDoNotConvertWechatIntoPasswordAccountOrMergeLegacyCustomer()
    {
        String phone="139"+String.format("%08d",Math.abs(tag.hashCode())%100000000);
        jdbc.update("INSERT INTO qc_customer(audience,phone,password_hash,contact_name,address,status) VALUES('consumer',?,?,'旧客户','旧地址','ACTIVE')",phone,passwords.encode("LegacyPass2026!"));
        long legacy=jdbc.queryForObject("SELECT id FROM qc_customer WHERE audience='consumer' AND phone=?",Long.class,phone);legacyCustomers.add(legacy);
        JsonNode wx=login("owner","consumer");
        Map<String,Object> profile=map("contactName","新联系人","phone",phone,"address","新地址","version",0,"authMethod","PASSWORD","openid","forged");
        assertEquals(200,call(HttpMethod.PUT,"/profile",profile,token(wx)).getStatusCodeValue());
        Map<String,Object> row=customer(id(wx));assertNull(row.get("phone"));assertNull(row.get("password_hash"));assertEquals("WECHAT",row.get("auth_method"));assertEquals(phone,row.get("contact_phone"));
        ResponseEntity<JsonNode> oldLogin=call(HttpMethod.POST,"/auth/login",map("audience","consumer","phone",phone,"password","LegacyPass2026!"),null);
        assertEquals(200,oldLogin.getStatusCodeValue());assertEquals(legacy,oldLogin.getBody().path("data").path("profile").path("id").asLong());
        assertEquals("旧客户",customer(legacy).get("contact_name"));assertEquals(tag+"_owner",jdbc.queryForObject("SELECT open_id FROM qc_wechat_identity WHERE customer_id=?",String.class,id(wx)));
        Map<String,Object> fallback=map("productId",product,"quantity",1,"idempotencyKey",tag);
        assertEquals(201,call(HttpMethod.POST,"/orders",fallback,token(wx)).getStatusCodeValue());
        assertEquals(phone,jdbc.queryForObject("SELECT contact_phone FROM qc_order WHERE customer_id=?",String.class,id(wx)));
    }

    @Test void additiveMigrationRunsTwiceAndPreservesLegacyPasswordAccount() throws Exception
    {
        String schema="qc_storefront_migration_"+tag.substring(4).toLowerCase(Locale.ROOT);
        assertTrue(schema.matches("qc_storefront_migration_[a-f0-9]{16}"));
        String hash=passwords.encode("LegacyMigration2026!");
        java.nio.file.Path script=Paths.get("..","sql","qicai_storefront_upgrade_20261017.sql").normalize();
        assertTrue(java.nio.file.Files.isRegularFile(script),"Run Maven using the ruoyi-admin module's basedir");
        try(java.sql.Connection connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection())
        {
            String original=connection.getCatalog();
            try
            {
                try(java.sql.Statement create=connection.createStatement()) {create.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
                connection.setCatalog(schema);
                // MySQL Statement captures the current database when constructed.
                // Construct it only after setCatalog, and prove the selected schema.
                try(java.sql.Statement statement=connection.createStatement())
                {
                try(java.sql.ResultSet selected=statement.executeQuery("SELECT DATABASE()")) {assertTrue(selected.next());assertEquals(schema,selected.getString(1));}
                statement.execute("CREATE TABLE qc_customer(id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,audience VARCHAR(16) NOT NULL,phone VARCHAR(20) NOT NULL,password_hash VARCHAR(100) NOT NULL,contact_name VARCHAR(60) NOT NULL,company_name VARCHAR(120),tax_id VARCHAR(32),address VARCHAR(255),status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',version INT NOT NULL DEFAULT 0,deleted TINYINT NOT NULL DEFAULT 0,UNIQUE KEY audience_phone(audience,phone),UNIQUE KEY tax(tax_id)) ENGINE=InnoDB");
                statement.execute("CREATE TABLE qc_wechat_identity(customer_id BIGINT NOT NULL,app_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,open_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,PRIMARY KEY(customer_id,app_id),KEY openid(app_id,open_id),FOREIGN KEY(customer_id) REFERENCES qc_customer(id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                try(java.sql.PreparedStatement insert=connection.prepareStatement("INSERT INTO qc_customer(audience,phone,password_hash,contact_name) VALUES('consumer','13900009999',?,'旧账号')")) {insert.setString(1,hash);insert.executeUpdate();}
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.FileSystemResource(script));
                statement.execute("INSERT INTO qc_customer(audience,auth_method,status) VALUES('consumer','WECHAT','ACTIVE')");
                statement.execute("INSERT INTO qc_customer(audience,auth_method,company_name,contact_name,contact_phone,tax_id,address,status) VALUES('business','WECHAT','合成企业','企业联系人','13900009999','913100001234567890','企业地址','PENDING')");
                statement.execute("INSERT INTO qc_wechat_account(app_id,open_id,consumer_customer_id,business_customer_id) VALUES('wx1234567890abcdef','synthetic-shared-owner',2,3)");
                statement.execute("INSERT INTO qc_wechat_identity(customer_id,app_id,open_id) VALUES(2,'wx1234567890abcdef','synthetic-shared-owner'),(3,'wx1234567890abcdef','synthetic-shared-owner')");
                Map<String,List<Map<String,Object>>> before=migrationRows(connection);
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.FileSystemResource(script));
                assertEquals(before,migrationRows(connection),"Repeated migration must preserve every legacy and WECHAT field and both identity mappings");
                try(java.sql.ResultSet result=statement.executeQuery("SELECT id,audience,phone,password_hash,contact_name,auth_method,contact_phone FROM qc_customer WHERE auth_method='PASSWORD'"))
                {
                    assertTrue(result.next());assertEquals(1,result.getLong("id"));assertEquals("13900009999",result.getString("phone"));assertEquals(hash,result.getString("password_hash"));
                    assertTrue(passwords.matches("LegacyMigration2026!",result.getString("password_hash")));assertEquals("旧账号",result.getString("contact_name"));assertEquals("PASSWORD",result.getString("auth_method"));assertNull(result.getString("contact_phone"));assertFalse(result.next());
                }
                try(java.sql.ResultSet result=statement.executeQuery("SELECT COUNT(*) FROM qc_customer WHERE auth_method='WECHAT' AND phone IS NULL AND password_hash IS NULL AND contact_name IS NULL")) {assertTrue(result.next());assertEquals(1,result.getInt(1));}
                try(java.sql.ResultSet result=statement.executeQuery("SELECT COUNT(*) FROM qc_customer WHERE auth_method='WECHAT' AND phone IS NULL AND password_hash IS NULL")) {assertTrue(result.next());assertEquals(2,result.getInt(1));}
                assertThrows(java.sql.SQLException.class,()->statement.execute("INSERT INTO qc_wechat_account(app_id,open_id) VALUES('wx1234567890abcdef','synthetic-shared-owner')"));
                assertThrows(java.sql.SQLException.class,()->statement.execute("INSERT INTO qc_wechat_account(app_id,open_id,consumer_customer_id) VALUES('wx1234567890abcdef','different-identity',2)"));
                assertThrows(java.sql.SQLException.class,()->statement.execute("INSERT INTO qc_wechat_account(app_id,open_id,business_customer_id) VALUES('wx1234567890abcdef','different-identity',3)"));
                assertThrows(java.sql.SQLException.class,()->statement.execute("INSERT INTO qc_wechat_identity(customer_id,app_id,open_id) VALUES(2,'wx1234567890abcdef','different-identity')"));
                assertEquals(before,migrationRows(connection),"Rejected duplicate mappings must not alter saved identities");
                }
            }
            finally
            {
                connection.setCatalog(original);
                try(java.sql.Statement cleanup=connection.createStatement()) {cleanup.execute("DROP DATABASE IF EXISTS "+schema);}
            }
        }
    }

    private static Map<String,List<Map<String,Object>>> migrationRows(java.sql.Connection connection) throws Exception
    {
        Map<String,List<Map<String,Object>>> tables=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:Arrays.asList("qc_customer","qc_wechat_account","qc_wechat_identity"))
        {
            List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();
            String order=table.equals("qc_customer")?"id":table.equals("qc_wechat_account")?"app_id,open_id":"customer_id,app_id";
            try(java.sql.Statement statement=connection.createStatement();java.sql.ResultSet result=statement.executeQuery("SELECT * FROM "+table+" ORDER BY "+order))
            {
                while(result.next())
                {
                    Map<String,Object> row=new LinkedHashMap<String,Object>();
                    for(int col=1;col<=result.getMetaData().getColumnCount();col++)row.put(result.getMetaData().getColumnName(col),result.getObject(col));
                    rows.add(row);
                }
            }
            tables.put(table,rows);
        }
        return tables;
    }

    @Test void businessConstraintFailureRollsBackNewMappingAndCustomerWithoutTouchingExistingApplication()
    {
        Map<String,Object> first=business(code("owner"));
        ResponseEntity<JsonNode> existing=call(HttpMethod.POST,"/auth/wechat/business-register",first,null);assertEquals(201,existing.getStatusCodeValue());
        long id=existing.getBody().path("data").path("profile").path("id").asLong();Map<String,Object> before=customer(id);
        Map<String,Object> collision=business(code("other"));
        assertEquals(409,call(HttpMethod.POST,"/auth/wechat/business-register",collision,null).getStatusCodeValue());
        assertEquals(before,customer(id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qc_wechat_account WHERE app_id=? AND open_id=?",Integer.class,config.getAppId(),tag+"_other"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_customer WHERE tax_id=?",Integer.class,first.get("taxId")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qc_wechat_identity WHERE open_id LIKE ?",Integer.class,tag+"%"));
    }

    @Test void trustedProxyHeaderReplacementPreservesRateLimitThroughActualForwardedHeaderFilter() throws Exception
    {
        // Boundary model only: the production nginx diff must independently prove
        // removal of Forwarded and replacement (not append) of X-Forwarded-For.
        assertEquals("203.0.113.1",forwardedRemote("for=203.0.113.1","198.51.100.12"));
        assertEquals("203.0.113.2",forwardedRemote("for=203.0.113.2","198.51.100.12"));
        String client="198.51.100."+(Math.abs(tag.hashCode()%200)+20);
        String key="qc:wechat:ip:"+QicaiAuthService.sha256(client);redis.delete(key);
        when(provider.exchangeCode(anyString())).thenThrow(new QicaiApiException(HttpStatus.BAD_GATEWAY,"synthetic exchange failure"));
        try
        {
            for(int i=0;i<61;i++)
            {
                // Edge discards every attacker-supplied for/XFF and supplies the
                // actual TCP client address. Spring sees the same client each time.
                String resolved=forwardedRemote(null,client);assertEquals(client,resolved);
                QicaiApiException denied=assertThrows(QicaiApiException.class,()->wechat.login(map("code",code("owner"),"audience","consumer"),resolved));
                assertEquals(i<60?HttpStatus.BAD_GATEWAY:HttpStatus.TOO_MANY_REQUESTS,denied.getStatus());
            }
            assertEquals("61",redis.opsForValue().get(key));assertTrue(redis.getExpire(key,TimeUnit.SECONDS)>0);
            verify(provider,times(60)).exchangeCode(anyString());
        }
        finally {redis.delete(key);}
    }

    private static String forwardedRemote(String forwarded,String xff) throws Exception
    {
        org.springframework.mock.web.MockHttpServletRequest request=new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");request.setRequestURI("/qicai/mobile/auth/wechat/login");
        if(forwarded!=null)request.addHeader("Forwarded",forwarded);request.addHeader("X-Forwarded-For",xff);
        java.util.concurrent.atomic.AtomicReference<String> remote=new java.util.concurrent.atomic.AtomicReference<String>();
        new org.springframework.web.filter.ForwardedHeaderFilter().doFilter(request,new org.springframework.mock.web.MockHttpServletResponse(),
                (req,res)->remote.set(req.getRemoteAddr()));return remote.get();
    }
}

package com.ruoyi.qicai.payment;

import com.alibaba.druid.pool.DruidDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.qicai.QicaiAuthService;
import com.ruoyi.qicai.QicaiWechatCodeService;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real isolated MySQL/service transactions with a deterministic two-connection barrier;
 * synthetic provider only. No web server, WeChat, production or schema writes. */
class WechatPaymentPoolIT
{
    static Map<String,Object> map(Object... values)
    {
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);
        return result;
    }

    @Test void independentPrepaymentsDoNotExhaustPoolWaitingForNestedConnections() throws Exception
    {
        assertEquals("1",System.getenv("QICAI_ISOLATED_TEST"));
        String url=System.getenv("QICAI_DB_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:13319/qicai_acceptance(?:\\?.*)?"));
        Path runtime=Paths.get(Objects.requireNonNull(System.getenv("QICAI_RUNTIME"))).toAbsolutePath().normalize();
        Path evidence=Paths.get(Objects.requireNonNull(System.getenv("QICAI_EVIDENCE_DIR"))).resolve("payment-pool-observation.json");
        assertTrue(Files.isDirectory(evidence.getParent()));
        assertFalse(Files.exists(evidence),"Use a new evidence directory; never replace the baseline");
        String tag="POOL"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        DruidDataSource ds=new DruidDataSource();
        ds.setUrl(url);ds.setUsername(System.getenv("QICAI_DB_USERNAME"));ds.setPassword(System.getenv("QICAI_DB_PASSWORD"));
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");ds.setMaxActive(2);ds.setInitialSize(2);ds.setMinIdle(0);ds.setMaxWait(1000);
        ds.setTestWhileIdle(false);ds.setTestOnBorrow(false);ds.setTestOnReturn(false);
        List<Long> customers=new ArrayList<Long>(),orders=new ArrayList<Long>();
        AtomicInteger activeAtBarrier=new AtomicInteger(),participants=new AtomicInteger(),providerCalls=new AtomicInteger();
        List<Map<String,Object>> outcomes=Collections.synchronizedList(new ArrayList<Map<String,Object>>());
        ExecutorService executor=Executors.newFixedThreadPool(2);
        JdbcTemplate setup=null;
        long product=0;
        try
        {
            ds.init();setup=new JdbcTemplate(ds);
            assertEquals(13319,setup.queryForObject("SELECT @@port",Integer.class));
            assertEquals("qicai_acceptance",setup.queryForObject("SELECT DATABASE()",String.class));
            assertEquals(runtime.resolve("mysql"),Paths.get(setup.queryForObject("SELECT @@datadir",String.class)).toAbsolutePath().normalize());
            // Fixtures represent two already-created, correctly reserved independent orders.
            setup.update("INSERT INTO qc_product(sku,name,category,unit,stock,business_price,consumer_price,status) VALUES(?,?,?,'item',8,1,1,'ACTIVE')",tag,tag,tag);
            product=setup.queryForObject("SELECT id FROM qc_product WHERE sku=?",Long.class,tag);
            String password=new BCryptPasswordEncoder().encode("SyntheticPoolOnly2026!");
            long phoneSuffix=ThreadLocalRandom.current().nextLong(10000000L,99999997L);
            for(int i=0;i<2;i++)
            {
                String marker=tag+"-"+i;
                String phone="173"+String.format(java.util.Locale.ROOT,"%08d",phoneSuffix+i);
                setup.update("INSERT INTO qc_customer(audience,auth_method,phone,password_hash,contact_name,address,status) VALUES('consumer','PASSWORD',?,?,?,'isolated','ACTIVE')",phone,password,marker);
                long customer=setup.queryForObject("SELECT id FROM qc_customer WHERE contact_name=?",Long.class,marker);
                customers.add(customer);
                setup.update("INSERT INTO qc_order(order_no,customer_id,audience,status,payment_status,subtotal_amount,discount_amount,payable_amount,contact_name,contact_phone,delivery_address,idempotency_key) VALUES(?,?,'consumer','PENDING_PAYMENT','UNPAID',1,0,1,'synthetic','13900000000','isolated',?)",marker,customer,marker);
                long order=setup.queryForObject("SELECT id FROM qc_order WHERE order_no=?",Long.class,marker);
                orders.add(order);
                setup.update("INSERT INTO qc_order_item(order_id,product_id,sku,product_name,unit,unit_price,quantity,line_amount) VALUES(?,?,?,?,'item',1,1,1)",order,product,tag,tag);
                setup.update("INSERT INTO qc_wechat_identity(customer_id,app_id,open_id) VALUES(?,'wx1234567890abcdef',?)",customer,"synthetic-"+marker);
            }
            CyclicBarrier barrier=new CyclicBarrier(2,()->activeAtBarrier.set(ds.getActiveCount()));
            Set<Long> gated=ConcurrentHashMap.newKeySet();
            JdbcTemplate observed=new JdbcTemplate(ds)
            {
                @Override public List<Map<String,Object>> queryForList(String sql,Object... args)
                {
                    List<Map<String,Object>> rows=super.queryForList(sql,args);
                    // Same production query, after customer/order locks and before send intent.
                    // Initial missing-row preparation is not gated. Each order gates once.
                    if(sql.equals("SELECT * FROM qc_payment WHERE order_id=?") && !rows.isEmpty()
                            && "PREPARED".equals(rows.get(0).get("status")) && gated.add(((Number)args[0]).longValue()))
                    {
                        participants.incrementAndGet();
                        try {barrier.await(6,TimeUnit.SECONDS);}
                        catch(Exception ex){throw new AssertionError("Both production transactions must reach the controlled boundary",ex);}
                    }
                    return rows;
                }
            };
            WechatPayProperties config=new WechatPayProperties();config.setEnabled(true);config.setAppId("wx1234567890abcdef");config.setMerchantId("1900000001");
            WechatPaymentGateway gateway=mock(WechatPaymentGateway.class);
            when(gateway.prepay(anyString(),anyInt(),anyString(),anyString())).thenAnswer(call->{providerCalls.incrementAndGet();return map("package","prepay_id=synthetic");});
            WechatPaymentService service=new WechatPaymentService(observed,config,gateway,new DataSourceTransactionManager(ds),mock(QicaiWechatCodeService.class));
            List<Future<Map<String,Object>>> futures=new ArrayList<Future<Map<String,Object>>>();
            for(int i=0;i<2;i++)
            {
                final long customer=customers.get(i),order=orders.get(i);
                futures.add(executor.submit(()->{
                    long started=System.nanoTime();
                    try
                    {
                        Map<String,Object> response=service.prepay(new QicaiAuthService.Principal("customer",customer,"consumer"),order);
                        return map("orderId",order,"success","prepay_id=synthetic".equals(response.get("package")),"elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
                    }
                    catch(Throwable failure)
                    {
                        // Do not serialize exception messages/SQL/request values or credentials.
                        return map("orderId",order,"success",false,"failureType",failure.getClass().getName(),"elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
                    }
                }));
            }
            for(Future<Map<String,Object>> future:futures)outcomes.add(future.get(15,TimeUnit.SECONDS));
            List<Map<String,Object>> states=new ArrayList<Map<String,Object>>();
            for(long order:orders)states.add(setup.queryForMap("SELECT order_id,status,uncertain_attempts FROM qc_payment WHERE order_id=?",order));
            Map<String,Object> result=map("at",Instant.now().toString(),"kind","real isolated service/MySQL, synthetic provider, deterministic two-connection barrier",
                    "maxActive",2,"maxWaitMillis",1000,"participants",participants.get(),"activeConnectionsAtBarrier",activeAtBarrier.get(),
                    "providerCalls",providerCalls.get(),"outcomes",outcomes,"paymentStates",states,"thirdPartyRequests",0);
            Files.write(evidence,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);
            assertEquals(2,participants.get(),"Barrier not exercised; no false pass");
            assertEquals(2,activeAtBarrier.get(),"Both outer transactions must hold the entire pool");
            assertTrue(outcomes.stream().allMatch(r->Boolean.TRUE.equals(r.get("success"))),"Prepayments must not need a second connection while holding the first; inspect saved observation");
            assertEquals(2,providerCalls.get());
            for(Map<String,Object> state:states){assertEquals("PENDING",state.get("status"));assertEquals(1,((Number)state.get("uncertain_attempts")).intValue());}
        }
        finally
        {
            executor.shutdownNow();executor.awaitTermination(10,TimeUnit.SECONDS);
            // Restricted own-fixture cleanup; evidence retains actual states before cleanup.
            if(setup!=null)
            {
                for(long id:orders){setup.update("DELETE FROM qc_payment WHERE order_id=?",id);setup.update("DELETE FROM qc_order_item WHERE order_id=?",id);setup.update("DELETE FROM qc_order WHERE id=?",id);}
                for(long id:customers){setup.update("DELETE FROM qc_wechat_identity WHERE customer_id=?",id);setup.update("DELETE FROM qc_customer WHERE id=?",id);}
                if(product!=0)setup.update("DELETE FROM qc_product WHERE id=?",product);
            }
            ds.close();
        }
    }
}

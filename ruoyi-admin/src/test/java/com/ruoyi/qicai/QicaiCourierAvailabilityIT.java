package com.ruoyi.qicai;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.RuoYiApplication;
import java.nio.file.Paths;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP/auth/MySQL checks against the explicitly verified disposable runtime. */
@SpringBootTest(classes=RuoYiApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"server.address=127.0.0.1","qicai.wechat-pay.enabled=false","qicai.wechat-login.enabled=false",
                "qicai.wechat-pay.maintenance-enabled=false","qicai.sms.dev-mode=false",
                "spring.redis.host=127.0.0.1","spring.redis.port=16399","spring.devtools.restart.enabled=false"})
@ActiveProfiles("qicai")
@ContextConfiguration(initializers=QicaiCatalogIT.IsolatedEnvironment.class)
class QicaiCourierAvailabilityIT
{
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired BCryptPasswordEncoder passwords;
    final List<Long> couriers=new ArrayList<>();
    final List<Long> customers=new ArrayList<>();
    final String password="SyntheticAvailability2026!";
    long courier,other;
    String phone,token;
    static final String PROFILE="SELECT id,phone,name,vehicle_no AS vehicleNo,status,available,version FROM qc_courier WHERE id=?";

    Map<String,Object> map(Object... values)
    {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);
        return result;
    }

    @BeforeEach void seed()
    {
        assertEquals(13319,jdbc.queryForObject("SELECT @@port",Integer.class));
        assertEquals("qicai_acceptance",jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(Paths.get(System.getenv("QICAI_RUNTIME"),"mysql").toAbsolutePath().normalize(),
                Paths.get(jdbc.queryForObject("SELECT @@datadir",String.class)).toAbsolutePath().normalize());
        long suffix=Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),99999998L);
        phone="174"+String.format(Locale.ROOT,"%08d",suffix);
        for(int i=0;i<2;i++)
        {
            String current="174"+String.format(Locale.ROOT,"%08d",suffix+i);
            jdbc.update("INSERT INTO qc_courier(phone,password_hash,name,vehicle_no,status,available) VALUES(?,?,'synthetic-availability','TEST','ACTIVE',0)",current,passwords.encode(password));
            couriers.add(jdbc.queryForObject("SELECT id FROM qc_courier WHERE phone=?",Long.class,current));
        }
        courier=couriers.get(0);other=couriers.get(1);token=login();
    }

    @AfterEach void cleanOwnFixtures()
    {
        for(long id:couriers){jdbc.update("DELETE FROM qc_session WHERE principal_type='courier' AND principal_id=?",id);jdbc.update("DELETE FROM qc_courier WHERE id=?",id);}
        for(long id:customers){jdbc.update("DELETE FROM qc_session WHERE principal_type='customer' AND principal_id=?",id);jdbc.update("DELETE FROM qc_customer WHERE id=?",id);}
    }

    ResponseEntity<JsonNode> call(HttpMethod method,String path,Object body,String currentToken)
    {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(currentToken!=null)headers.set("X-Qicai-Token",currentToken);
        return http.exchange("/qicai/mobile"+path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }

    String login()
    {
        ResponseEntity<JsonNode> result=call(HttpMethod.POST,"/courier/login",map("phone",phone,"password",password),null);
        assertEquals(200,result.getStatusCodeValue());
        JsonNode available=result.getBody().path("data").path("profile").path("available");
        assertTrue(available.isBoolean(),"Courier login must return the same boolean availability contract");
        Integer stored=jdbc.queryForObject("SELECT available FROM qc_courier WHERE id=?",Integer.class,courier);
        assertTrue(Integer.valueOf(0).equals(stored)||Integer.valueOf(1).equals(stored));
        assertEquals(stored==1,available.booleanValue());
        String value=result.getBody().path("data").path("token").asText();
        assertFalse(value.isEmpty());return value;
    }

    JsonNode read(String currentToken)
    {
        ResponseEntity<JsonNode> result=call(HttpMethod.GET,"/courier/availability?courierId="+other,null,currentToken);
        assertEquals(200,result.getStatusCodeValue());assertEquals(200,result.getBody().path("code").asInt());
        JsonNode value=result.getBody().path("data");Set<String> fields=new HashSet<>();value.fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<>(Arrays.asList("id","phone","name","vehicleNo","status","available","version")),fields);
        assertEquals(courier,value.path("id").asLong());
        Map<String,Object> stored=jdbc.queryForMap(PROFILE,courier);
        Object storedAvailability=stored.get("available");
        assertTrue(storedAvailability instanceof Number,"DB available must be numeric 0 or 1");
        java.math.BigDecimal numericAvailability=new java.math.BigDecimal(storedAvailability.toString());
        assertTrue(numericAvailability.compareTo(java.math.BigDecimal.ZERO)==0
                ||numericAvailability.compareTo(java.math.BigDecimal.ONE)==0,"DB available must be exactly 0 or 1");
        assertTrue(value.path("available").isBoolean(),"API available must be a JSON boolean");
        assertEquals(numericAvailability.compareTo(java.math.BigDecimal.ONE)==0,value.path("available").booleanValue());
        assertEquals(((Number)stored.get("version")).intValue(),value.path("version").asInt());
        return value;
    }

    @Test void refreshReentryAndNewLoginReadPersistedAvailabilityWithoutTouchingAnotherCourier()
    {
        Map<String,Object> before=jdbc.queryForMap(PROFILE,courier),otherBefore=jdbc.queryForMap(PROFILE,other);
        assertFalse(read(token).path("available").asBoolean());assertEquals(before,jdbc.queryForMap(PROFILE,courier));
        JsonNode updated=call(HttpMethod.PUT,"/courier/availability",map("available",true),token).getBody();
        assertEquals(200,updated.path("code").asInt());assertTrue(updated.path("data").path("available").asBoolean());
        assertTrue(read(token).path("available").asBoolean());
        String second=login();
        assertEquals(200,call(HttpMethod.PUT,"/courier/availability",map("available",false),second).getStatusCodeValue());
        assertFalse(read(token).path("available").asBoolean());assertFalse(read(login()).path("available").asBoolean());
        assertEquals(((Number)before.get("version")).intValue()+2,read(token).path("version").asInt());
        assertEquals(otherBefore,jdbc.queryForMap(PROFILE,other));
    }

    @Test void anonymousWrongRoleExpiredDisabledAndDeletedSessionsAreRejected()
    {
        assertEquals(401,call(HttpMethod.GET,"/courier/availability",null,null).getStatusCodeValue());
        String customerPhone="175"+phone.substring(3);
        jdbc.update("INSERT INTO qc_customer(audience,phone,password_hash,contact_name,address,status) VALUES('consumer',?,?,'synthetic','isolated','ACTIVE')",customerPhone,passwords.encode(password));
        customers.add(jdbc.queryForObject("SELECT id FROM qc_customer WHERE audience='consumer' AND phone=?",Long.class,customerPhone));
        JsonNode login=call(HttpMethod.POST,"/auth/login",map("audience","consumer","phone",customerPhone,"password",password),null).getBody();
        assertEquals(200,login.path("code").asInt());
        assertEquals(403,call(HttpMethod.GET,"/courier/availability",null,login.path("data").path("token").asText()).getStatusCodeValue());
        jdbc.update("UPDATE qc_session SET expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE principal_type='courier' AND principal_id=?",courier);
        assertEquals(401,call(HttpMethod.GET,"/courier/availability",null,token).getStatusCodeValue());
        token=login();jdbc.update("UPDATE qc_courier SET status='DISABLED' WHERE id=?",courier);
        assertEquals(401,call(HttpMethod.GET,"/courier/availability",null,token).getStatusCodeValue());
        jdbc.update("UPDATE qc_courier SET status='ACTIVE',deleted=1 WHERE id=?",courier);
        assertEquals(401,call(HttpMethod.GET,"/courier/availability",null,token).getStatusCodeValue());
    }
}

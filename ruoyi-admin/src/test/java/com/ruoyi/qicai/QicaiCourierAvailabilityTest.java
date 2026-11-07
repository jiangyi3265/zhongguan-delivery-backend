package com.ruoyi.qicai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.framework.config.ServerConfig;
import com.ruoyi.qicai.payment.WechatPaymentService;
import com.ruoyi.web.controller.qicai.QicaiMobileController;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real controller, auth and service with JDBC doubles. Not a database/browser test. */
class QicaiCourierAvailabilityTest
{
    static final String PROFILE="SELECT id,phone,name,vehicle_no AS vehicleNo,status,available,version FROM qc_courier WHERE id=? AND deleted=0";
    static final String SESSION="SELECT principal_type,principal_id,audience,expires_at FROM qc_session WHERE token_hash=? LIMIT 1";
    JdbcTemplate jdbc; MockMvc mvc; StringRedisTemplate redis; BCryptPasswordEncoder encoder;

    @BeforeEach void setup()
    {
        jdbc=mock(JdbcTemplate.class);
        encoder=new BCryptPasswordEncoder();redis=mock(StringRedisTemplate.class);
        QicaiAuthService auth=new QicaiAuthService(jdbc,encoder,mock(QicaiSmsVerifier.class),redis);
        mvc=MockMvcBuilders.standaloneSetup(new QicaiMobileController(auth,new QicaiService(jdbc,auth,encoder,mock(WechatPaymentService.class)),
                mock(ServerConfig.class),mock(QicaiHealthService.class),mock(QicaiWechatAuthService.class)))
                .setControllerAdvice(new QicaiExceptionHandler()).build();
    }

    Map<String,Object> row(Object... values)
    {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);
        return result;
    }

    void session(String type,LocalDateTime expires,boolean active)
    {
        // JdbcTemplate's varargs contain one String here; an Object[] matcher does
        // not match that flattened argument in the repository's Mockito version.
        when(jdbc.queryForList(SESSION,QicaiAuthService.sha256("synthetic")))
                .thenReturn(Collections.singletonList(row("principal_type",type,"principal_id",41L,"audience","courier","expires_at",expires)));
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM qc_courier"),eq(Integer.class),eq(41L))).thenReturn(active?1:0);
    }

    @Test void anonymousReadDoesNotReachPersistence() throws Exception
    {
        mvc.perform(get("/qicai/mobile/courier/availability")).andExpect(status().isUnauthorized());
        verifyNoInteractions(jdbc);
    }

    @Test void customerCannotUseTheCourierRead() throws Exception
    {
        session("customer",LocalDateTime.now().plusHours(1),true);
        mvc.perform(get("/qicai/mobile/courier/availability").header("X-Qicai-Token","synthetic"))
                .andExpect(status().isForbidden());
        verify(jdbc).queryForList(SESSION,QicaiAuthService.sha256("synthetic"));
        verify(jdbc,never()).queryForList(eq(PROFILE),anyLong());
    }

    @Test void eachReadReturnsCurrentOwnDatabaseStateAndOnlyThePutProjection() throws Exception
    {
        session("courier",LocalDateTime.now().plusHours(1),true);
        when(jdbc.queryForList(PROFILE,41L)).thenReturn(
                Collections.singletonList(row("id",41L,"phone","13900000000","name","synthetic","vehicleNo","TEST","status","ACTIVE","available",0,"version",3)),
                Collections.singletonList(row("id",41L,"phone","13900000000","name","synthetic","vehicleNo","TEST","status","ACTIVE","available",1,"version",4)));
        String first=mvc.perform(get("/qicai/mobile/courier/availability").param("courierId","999").header("X-Qicai-Token","synthetic"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(41))
                .andExpect(jsonPath("$.data.available").value(false)).andExpect(jsonPath("$.data.version").value(3))
                .andReturn().getResponse().getContentAsString();
        JsonNode profile=new ObjectMapper().readTree(first).path("data");
        assertEquals(7,profile.size());assertTrue(profile.path("available").isBoolean());assertFalse(profile.has("password_hash"));assertFalse(profile.has("password"));
        mvc.perform(get("/qicai/mobile/courier/availability").header("X-Qicai-Token","synthetic"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").isBoolean())
                .andExpect(jsonPath("$.data.available").value(true)).andExpect(jsonPath("$.data.version").value(4));
        verify(jdbc,times(2)).queryForList(PROFILE,41L);
        assertTrue(mockingDetails(jdbc).getInvocations().stream().noneMatch(invocation ->
                invocation.getMethod().getName().equals("update")
                && ((String)invocation.getArgument(0)).startsWith("UPDATE qc_courier")));
    }

    @Test void putUsesTheSameBooleanProjectionAndWritesOnlyNumericDatabaseState() throws Exception
    {
        session("courier",LocalDateTime.now().plusHours(1),true);
        for (int persisted : new int[]{0,1})
        {
            when(jdbc.queryForList(PROFILE,41L)).thenReturn(Collections.singletonList(
                    row("id",41L,"phone","13900000000","name","synthetic","vehicleNo","TEST","status","ACTIVE","available",persisted,"version",4)));
            mvc.perform(put("/qicai/mobile/courier/availability").header("X-Qicai-Token","synthetic")
                    .contentType("application/json").content("{\"available\":"+(persisted==1)+"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").isBoolean())
                    .andExpect(jsonPath("$.data.available").value(persisted==1));
            verify(jdbc).update("UPDATE qc_courier SET available=?,version=version+1 WHERE id=? AND status='ACTIVE' AND deleted=0",persisted,41L);
        }
    }

    @Test void invalidPersistedStateIsRejectedInsteadOfAppearingReady() throws Exception
    {
        session("courier",LocalDateTime.now().plusHours(1),true);
        for (Object invalid : Arrays.asList(-1,2,null,"1",new java.math.BigDecimal("0.5"),Double.NaN))
        {
            when(jdbc.queryForList(PROFILE,41L)).thenReturn(Collections.singletonList(
                    row("id",41L,"phone","13900000000","name","synthetic","vehicleNo","TEST","status","ACTIVE","available",invalid,"version",3)));
            mvc.perform(get("/qicai/mobile/courier/availability").header("X-Qicai-Token","synthetic"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Test void jdbcNumericAndBooleanMappingsHaveOneStrictMeaning()
    {
        assertFalse(QicaiAuthService.courierAvailable(0));assertFalse(QicaiAuthService.courierAvailable(false));
        assertTrue(QicaiAuthService.courierAvailable(1L));assertTrue(QicaiAuthService.courierAvailable(true));
        assertFalse(QicaiAuthService.courierAvailable(java.math.BigDecimal.ZERO));
        assertTrue(QicaiAuthService.courierAvailable(new java.math.BigDecimal("1.0")));
        for (Object invalid : Arrays.asList(-1,2,null,"1",new java.math.BigDecimal("1.1"),Double.POSITIVE_INFINITY))
            assertEquals(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    assertThrows(QicaiApiException.class,()->QicaiAuthService.courierAvailable(invalid)).getStatus());
    }

    @Test void loginNormalizesNumericStateAndNeverIssuesSessionForInvalidState() throws Exception
    {
        org.springframework.data.redis.core.ValueOperations<String,String> counters=mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(counters);
        String password=encoder.encode("SyntheticAvailability2026!");
        for(int stored:new int[]{0,1,2})
        {
            when(jdbc.queryForList("SELECT id,password_hash,name,vehicle_no,status,available FROM qc_courier WHERE phone=? AND deleted=0 LIMIT 1 FOR UPDATE","13900000000"))
                    .thenReturn(Collections.singletonList(row("id",41L,"password_hash",password,"name","synthetic","vehicle_no","TEST","status","ACTIVE","available",stored)));
            int sessionsBefore=(int)mockingDetails(jdbc).getInvocations().stream().filter(invocation->
                    invocation.getMethod().getName().equals("update")&&((String)invocation.getArgument(0)).startsWith("INSERT INTO qc_session")).count();
            org.springframework.test.web.servlet.ResultActions result=mvc.perform(post("/qicai/mobile/courier/login")
                    .contentType("application/json").content("{\"phone\":\"13900000000\",\"password\":\"SyntheticAvailability2026!\"}"));
            if(stored<2) result.andExpect(status().isOk()).andExpect(jsonPath("$.data.profile.available").isBoolean())
                    .andExpect(jsonPath("$.data.profile.available").value(stored==1)).andExpect(jsonPath("$.data.profile.password_hash").doesNotExist());
            else result.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.data").doesNotExist());
            long sessionsAfter=mockingDetails(jdbc).getInvocations().stream().filter(invocation->
                    invocation.getMethod().getName().equals("update")&&((String)invocation.getArgument(0)).startsWith("INSERT INTO qc_session")).count();
            assertEquals(sessionsBefore+(stored<2?1:0),sessionsAfter);
        }
    }

    @Test void expiredOrDisabledCourierCannotReadAvailability() throws Exception
    {
        session("courier",LocalDateTime.now().minusMinutes(1),true);
        mvc.perform(get("/qicai/mobile/courier/availability").header("X-Qicai-Token","synthetic"))
                .andExpect(status().isUnauthorized());
        verify(jdbc).update("DELETE FROM qc_session WHERE token_hash=?",QicaiAuthService.sha256("synthetic"));
        session("courier",LocalDateTime.now().plusHours(1),false);
        mvc.perform(get("/qicai/mobile/courier/availability").header("X-Qicai-Token","synthetic"))
                .andExpect(status().isUnauthorized());
        verify(jdbc).queryForObject("SELECT COUNT(*) FROM qc_courier WHERE id=? AND deleted=0 AND status='ACTIVE'",Integer.class,41L);
        verify(jdbc,never()).queryForList(eq(PROFILE),anyLong());
    }
}

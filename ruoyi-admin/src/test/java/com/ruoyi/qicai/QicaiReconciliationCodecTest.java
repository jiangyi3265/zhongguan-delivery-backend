package com.ruoyi.qicai;

import com.ruoyi.qicai.payment.WechatPayProperties;
import com.ruoyi.web.controller.qicai.QicaiReconciliationController;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual controller/service JSON boundary. Security/SQL behavior is covered by extensions-browser. */
class QicaiReconciliationCodecTest {
    private Map<String,Object> row(String key,String value) { Map<String,Object> row=new LinkedHashMap<>();row.put("id",7L);row.put(key,new BigDecimal(value));return row; }
    private MockMvc mvc(JdbcTemplate jdbc) { return MockMvcBuilders.standaloneSetup(new QicaiReconciliationController(new QicaiReconciliationService(jdbc,new WechatPayProperties()))).build(); }
    @Test void totalsCrossJsonAsExactDecimalStrings() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(7L))).thenReturn(Collections.singletonList(row("orderAmount","9999999999999999.99")));
        mvc(jdbc).perform(get("/qicai/admin/reconciliation/7")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderAmount").isString()).andExpect(jsonPath("$.data.orderAmount").value("9999999999999999.99"))
            .andExpect(jsonPath("$.data.id").value(7));
    }
    @Test void rowMoneyAndNegativeFeesKeepPrecisionAndScale() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        Map<String,Object> item=row("billAmount","4999999999999999.99");item.put("feeAmount",new BigDecimal("-7.80"));item.put("localAmount",new BigDecimal("0.00"));
        when(jdbc.queryForList(anyString(),eq(7L))).thenReturn(Collections.singletonList(item));
        when(jdbc.queryForList(anyString(),eq(7L),eq(20),eq(0L))).thenReturn(Collections.singletonList(item));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(7L))).thenReturn(1L);
        mvc(jdbc).perform(get("/qicai/admin/reconciliation/7/items")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rows[0].billAmount").isString()).andExpect(jsonPath("$.data.rows[0].billAmount").value("4999999999999999.99"))
            .andExpect(jsonPath("$.data.rows[0].feeAmount").value("-7.80")).andExpect(jsonPath("$.data.rows[0].localAmount").value("0.00"));
    }
}

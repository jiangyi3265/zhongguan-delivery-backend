package com.ruoyi.qicai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.framework.config.ServerConfig;
import com.ruoyi.qicai.payment.WechatPaymentService;
import com.ruoyi.web.controller.qicai.QicaiMobileController;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real controller/service/auth logic with JDBC doubles; actual SQL is covered by QicaiCatalogIT. */
class QicaiCatalogTest
{
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    private final String displayName = "COALESCE((SELECT n.consumer_name FROM qc_product_name n WHERE n.product_id=qc_product.id),name)";
    private final String projection = "SELECT id,sku," + displayName + " AS name,subtitle,category,COALESCE((SELECT u.consumer_unit FROM qc_product_unit u WHERE u.product_id=qc_product.id),unit) AS unit,consumer_price AS price,stock,'consumer' AS audience FROM qc_product";

    @BeforeEach void setup()
    {
        jdbc = mock(JdbcTemplate.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        QicaiAuthService auth = new QicaiAuthService(jdbc, encoder,
                mock(QicaiSmsVerifier.class), mock(StringRedisTemplate.class));
        QicaiService service = new QicaiService(jdbc, auth, encoder, mock(WechatPaymentService.class));
        mvc = MockMvcBuilders.standaloneSetup(new QicaiMobileController(auth, service,
                mock(ServerConfig.class), mock(QicaiHealthService.class), mock(QicaiWechatAuthService.class)))
                .setControllerAdvice(new QicaiExceptionHandler()).build();
    }

    private Map<String, Object> product()
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", 41L); row.put("sku", "CATALOG-41"); row.put("name", "目录商品");
        row.put("subtitle", "展示说明"); row.put("category", "测试分类"); row.put("unit", "件");
        row.put("price", new java.math.BigDecimal("24.68")); row.put("stock", 31); row.put("audience", "consumer");
        return row;
    }

    @Test void anonymousListUsesOnlyPublicProjection() throws Exception
    {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(Collections.singletonList(product()));
        String body = mvc.perform(get("/qicai/mobile/catalog/products"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].sku").value("CATALOG-41"))
                .andReturn().getResponse().getContentAsString();
        JsonNode row = json.readTree(body).path("data").get(0);
        // New storefront contract explicitly publishes retail price/stock, never B price.
        assertEquals(9, row.size());
        assertEquals(31, row.path("stock").asInt()); assertEquals("24.68", row.path("price").asText());
        assertEquals("consumer", row.path("audience").asText());
        assertFalse(row.has("businessPrice")); assertFalse(row.has("consumerPrice"));
        verify(jdbc).queryForList(eq(projection + " WHERE deleted=0 AND status='ACTIVE' ORDER BY id LIMIT 101"), any(Object[].class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void anonymousDetailDoesNotRequireCustomerOrReturnAccountPrice() throws Exception
    {
        when(jdbc.queryForList(projection + " WHERE id=? AND deleted=0 AND status='ACTIVE'", 41L))
                .thenReturn(Collections.singletonList(product()));
        mvc.perform(get("/qicai/mobile/catalog/products/41"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(41))
                .andExpect(jsonPath("$.data.name").value("目录商品"))
                .andExpect(jsonPath("$.data.price").value(24.68))
                .andExpect(jsonPath("$.data.audience").value("consumer"))
                .andExpect(jsonPath("$.data.businessPrice").doesNotExist());
        verify(jdbc).queryForList(projection + " WHERE id=? AND deleted=0 AND status='ACTIVE'", 41L);
        verifyNoMoreInteractions(jdbc);
    }

    @Test void keywordAndCategoryStayBoundParameters() throws Exception
    {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());
        String keyword = "' OR 1=1 --";
        mvc.perform(get("/qicai/mobile/catalog/products").param("keyword", " " + keyword + " ")
                .param("category", " 测试分类 "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        verify(jdbc).queryForList(projection + " WHERE deleted=0 AND status='ACTIVE'"
                + " AND (" + displayName + " LIKE ? OR sku LIKE ?) AND category=? ORDER BY id LIMIT 101",
                "%" + keyword + "%", "%" + keyword + "%", "测试分类");
        verifyNoMoreInteractions(jdbc);
    }

    @Test void hiddenOrUnknownProductHasControlled404() throws Exception
    {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());
        mvc.perform(get("/qicai/mobile/catalog/products/999999"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("商品不存在或已下架"));
    }

    @Test void originalAccountAndBusinessReadsStillRejectAnonymousBeforeDatabase() throws Exception
    {
        for (String path : Arrays.asList("/products", "/products/41", "/profile", "/orders",
                "/orders/41", "/coupons", "/courier/tasks", "/courier/tasks/41"))
        {
            mvc.perform(get("/qicai/mobile" + path))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        }
        verifyNoInteractions(jdbc);
    }

    @Test void originalBusinessWritesStillRejectAnonymousBeforeDatabase() throws Exception
    {
        for (String path : Arrays.asList("/orders", "/orders/41/receipt", "/coupons/41/claim"))
        {
            mvc.perform(post("/qicai/mobile" + path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        }
        verifyNoInteractions(jdbc);
    }
}

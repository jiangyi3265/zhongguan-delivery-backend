package com.ruoyi.qicai;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.RuoYiApplication;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/security/SQL integration. Requires the existing isolated acceptance environment. */
@SpringBootTest(classes = RuoYiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "qicai.wechat-pay.enabled=false",
                "qicai.wechat-pay.maintenance-enabled=false", "qicai.sms.dev-mode=false",
                "spring.redis.host=127.0.0.1", "spring.redis.port=16399", "spring.devtools.restart.enabled=false"})
@ActiveProfiles("qicai")
@ContextConfiguration(initializers = QicaiCatalogIT.IsolatedEnvironment.class)
class QicaiCatalogIT
{
    public static class IsolatedEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext>
    {
        @Override public void initialize(ConfigurableApplicationContext context)
        {
            String url = System.getenv("QICAI_DB_URL");
            if (!"1".equals(System.getenv("QICAI_ISOLATED_TEST")) || url == null
                    || !url.startsWith("jdbc:mysql://127.0.0.1:13319/qicai_acceptance?")
                    || System.getenv("QICAI_RUNTIME") == null)
            {
                throw new IllegalStateException("Catalog IT requires the isolated acceptance database/runtime");
            }
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired BCryptPasswordEncoder passwords;
    private static final Set<String> PUBLIC_FIELDS = new HashSet<String>(
            Arrays.asList("id", "sku", "name", "subtitle", "category", "unit", "price", "stock", "audience"));
    private final List<Long> productIds = new ArrayList<Long>();
    private final List<Long> customerIds = new ArrayList<Long>();
    private String tag;
    private long active, second, inactive, deleted;

    @BeforeEach void seed()
    {
        assertEquals(13319, jdbc.queryForObject("SELECT @@port", Integer.class));
        assertEquals("qicai_acceptance", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(Paths.get(System.getenv("QICAI_RUNTIME"), "mysql").toAbsolutePath().normalize(),
                Paths.get(jdbc.queryForObject("SELECT @@datadir", String.class)).toAbsolutePath().normalize());
        tag = "CAT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        active = product("FIRST", "Alpha", tag + "-A", "ACTIVE", 0);
        second = product("SECOND", "Beta", tag + "-B", "ACTIVE", 0);
        inactive = product("HIDDEN", "Hidden", tag + "-A", "INACTIVE", 0);
        deleted = product("DELETED", "Deleted", tag + "-A", "ACTIVE", 1);
    }

    private long product(String suffix, String name, String category, String status, int removed)
    {
        String sku = tag + "-" + suffix;
        jdbc.update("INSERT INTO qc_product(sku,name,subtitle,category,unit,stock,business_price,consumer_price,status,deleted)"
                + " VALUES(?,?,?,?,'case',31,13.37,24.68,?,?)", sku, tag + " " + name, "Real persisted description", category, status, removed);
        long id = jdbc.queryForObject("SELECT id FROM qc_product WHERE sku=?", Long.class, sku);
        productIds.add(id);
        return id;
    }

    @AfterEach void cleanupOwnFixtures()
    {
        for (long id : customerIds)
        {
            jdbc.update("DELETE FROM qc_session WHERE principal_type='customer' AND principal_id=?", id);
            jdbc.update("DELETE FROM qc_customer WHERE id=?", id);
        }
        for (long id : productIds) jdbc.update("DELETE FROM qc_product WHERE id=?", id);
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body, String token)
    {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.set("X-Qicai-Token", token);
        return http.exchange("/qicai/mobile" + path, method, new HttpEntity<Object>(body, headers), JsonNode.class);
    }

    private JsonNode list(String keyword, String category)
    {
        String path = "/catalog/products?keyword={keyword}&category={category}";
        ResponseEntity<JsonNode> result = http.getForEntity("/qicai/mobile" + path, JsonNode.class, keyword, category);
        assertEquals(200, result.getStatusCodeValue()); assertEquals(200, result.getBody().path("code").asInt());
        return result.getBody().path("data");
    }

    private Set<Long> ids(JsonNode rows)
    {
        assertTrue(rows.isArray()); Set<Long> ids = new HashSet<Long>();
        for (JsonNode row : rows) { assertPublic(row); assertTrue(ids.add(row.path("id").asLong())); }
        return ids;
    }

    private void assertPublic(JsonNode row)
    {
        Set<String> fields = new HashSet<String>(); row.fieldNames().forEachRemaining(fields::add);
        assertEquals(PUBLIC_FIELDS, fields, "Storefront exposes retail price/stock, not enterprise pricing or customer fields");
        Map<String, Object> stored = jdbc.queryForMap("SELECT id,sku,name,subtitle,category,unit,consumer_price,stock FROM qc_product WHERE id=?", row.path("id").asLong());
        assertEquals(0, ((java.math.BigDecimal) stored.get("consumer_price")).compareTo(row.path("price").decimalValue()));
        assertEquals(((Number) stored.get("stock")).intValue(), row.path("stock").asInt());
        assertEquals("consumer", row.path("audience").asText());
        assertEquals(((Number) stored.get("id")).longValue(), row.path("id").asLong());
        for (String key : Arrays.asList("sku", "name", "subtitle", "category", "unit"))
        {
            if (stored.get(key) == null) assertTrue(row.get(key).isNull());
            else assertEquals(stored.get(key).toString(), row.path(key).asText());
        }
    }

    @Test void categoriesFollowAdminDataWithoutPageOrSearchLimits()
    {
        jdbc.update("UPDATE qc_product SET category=? WHERE id IN (?,?)", tag+"-HIDDEN", inactive, deleted);
        ResponseEntity<JsonNode> response = call(HttpMethod.GET, "/catalog/categories?keyword=not-found&pageSize=1", null, null);
        assertEquals(200, response.getStatusCodeValue());
        Set<String> categories = new HashSet<String>();
        response.getBody().path("data").forEach(value -> { assertTrue(value.isTextual()); categories.add(value.asText()); });
        assertTrue(categories.contains(tag+"-A")); assertTrue(categories.contains(tag+"-B"));
        assertFalse(categories.contains(tag+"-HIDDEN"));
        jdbc.update("UPDATE qc_product SET category=? WHERE id=?", tag+"-NEW", active);
        categories.clear();
        call(HttpMethod.GET, "/catalog/categories", null, null).getBody().path("data").forEach(value -> categories.add(value.asText()));
        assertFalse(categories.contains(tag+"-A")); assertTrue(categories.contains(tag+"-NEW"));
        assertEquals(400, call(HttpMethod.GET, "/catalog/categories?brandId=0", null, null).getStatusCodeValue());
        assertTrue(call(HttpMethod.GET, "/catalog/categories?brandId="+Long.MAX_VALUE, null, null).getBody().path("data").isEmpty());
    }

    @Test void anonymousListAndDetailReadDatabaseWithExactPublicFields()
    {
        assertEquals(new HashSet<Long>(Arrays.asList(active, second)), ids(list(tag, "")));
        ResponseEntity<JsonNode> result = call(HttpMethod.GET, "/catalog/products/" + active, null, null);
        assertEquals(200, result.getStatusCodeValue()); assertPublic(result.getBody().path("data"));
        // A caller-supplied audience or stale session cannot turn the public API into a price endpoint.
        result = call(HttpMethod.GET, "/catalog/products/" + active + "?audience=business", null, "invalid-session");
        assertEquals(200, result.getStatusCodeValue()); assertPublic(result.getBody().path("data"));
    }

    @Test void nameSkuCategoryAndCombinedSearchUseActualSql()
    {
        assertEquals(new HashSet<Long>(Arrays.asList(active)), ids(list(tag + " Alpha", "")));
        assertEquals(new HashSet<Long>(Arrays.asList(second)), ids(list(tag + "-SECOND", "")));
        assertEquals(new HashSet<Long>(Arrays.asList(active)), ids(list("", tag + "-A")));
        assertTrue(ids(list(tag + " Alpha", tag + "-B")).isEmpty());
        assertTrue(ids(list("' OR 1=1 --", "")).isEmpty());
        assertTrue(ids(list(tag, "' OR 1=1 --")).isEmpty());
        assertEquals(new HashSet<Long>(Arrays.asList(active)), ids(list("  " + tag + " Alpha  ", " " + tag + "-A ")));
    }

    @Test void inactiveDeletedUnknownAndInvalidIdsCannotBeRead()
    {
        for (long id : Arrays.asList(inactive, deleted, Long.MAX_VALUE, 0L, -1L))
        {
            ResponseEntity<JsonNode> result = call(HttpMethod.GET, "/catalog/products/" + id, null, null);
            assertEquals(404, result.getStatusCodeValue()); assertEquals(404, result.getBody().path("code").asInt());
            assertFalse(result.getBody().has("data"));
        }
        assertEquals(400, call(HttpMethod.GET, "/catalog/products/not-a-number", null, null).getStatusCodeValue());
    }

    @Test void subsequentReadsFollowProductRemovalAndRestoreWithoutLogin()
    {
        assertEquals(200, call(HttpMethod.GET, "/catalog/products/" + active, null, null).getStatusCodeValue());
        jdbc.update("UPDATE qc_product SET status='INACTIVE' WHERE id=?", active);
        assertEquals(404, call(HttpMethod.GET, "/catalog/products/" + active, null, null).getStatusCodeValue());
        assertEquals(new HashSet<Long>(Arrays.asList(second)), ids(list(tag, "")));
        jdbc.update("UPDATE qc_product SET status='ACTIVE',subtitle=NULL WHERE id=?", active);
        ResponseEntity<JsonNode> result = call(HttpMethod.GET, "/catalog/products/" + active, null, null);
        assertEquals(200, result.getStatusCodeValue()); assertPublic(result.getBody().path("data"));
        assertTrue(result.getBody().path("data").get("subtitle").isNull());
    }

    @Test void anonymousAccountAndBusinessAccessRemain401WithNoOrderOrStockWrite()
    {
        for (String path : Arrays.asList("/products", "/products/" + active, "/profile", "/orders", "/orders/" + active,
                "/coupons", "/courier/tasks", "/courier/tasks/" + active))
            assertEquals(401, call(HttpMethod.GET, path, null, null).getStatusCodeValue(), path);
        Map<String, Object> order = new LinkedHashMap<String, Object>();
        order.put("productId", active); order.put("quantity", 1); order.put("idempotencyKey", tag); order.put("deliveryAddress", "synthetic");
        assertEquals(401, call(HttpMethod.POST, "/orders", order, null).getStatusCodeValue());
        assertEquals(401, call(HttpMethod.POST, "/orders/" + active + "/receipt", new LinkedHashMap<String, Object>(), null).getStatusCodeValue());
        assertEquals(401, call(HttpMethod.POST, "/coupons/1/claim", new LinkedHashMap<String, Object>(), null).getStatusCodeValue());
        assertEquals(31, jdbc.queryForObject("SELECT stock FROM qc_product WHERE id=?", Integer.class, active));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM qc_order_item WHERE product_id=?", Integer.class, active));
    }

    @Test void authenticatedBusinessAndConsumerPricesRemainDistinctAndPendingStillForbidden()
    {
        for (String audience : Arrays.asList("consumer", "business"))
        {
            String phone = "139" + String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100000000L));
            String password = "CatalogSynthetic2026!";
            jdbc.update("INSERT INTO qc_customer(audience,phone,password_hash,contact_name,address,status) VALUES(?,?,?,'catalog-fixture','synthetic','ACTIVE')",
                    audience, phone, passwords.encode(password));
            long customer = jdbc.queryForObject("SELECT id FROM qc_customer WHERE audience=? AND phone=?", Long.class, audience, phone);
            customerIds.add(customer);
            Map<String, Object> credentials = new LinkedHashMap<String, Object>();
            credentials.put("audience", audience); credentials.put("phone", phone); credentials.put("password", password);
            ResponseEntity<JsonNode> login = call(HttpMethod.POST, "/auth/login", credentials, null);
            assertEquals(200, login.getStatusCodeValue());
            String token = login.getBody().path("data").path("token").asText(); assertFalse(token.isEmpty());
            ResponseEntity<JsonNode> priced = call(HttpMethod.GET, "/products/" + active, null, token);
            assertEquals(200, priced.getStatusCodeValue());
            assertEquals("business".equals(audience) ? "13.37" : "24.68", priced.getBody().path("data").path("price").asText());
            assertEquals(31, priced.getBody().path("data").path("stock").asInt());
            assertPublic(call(HttpMethod.GET, "/catalog/products/" + active, null, token).getBody().path("data"));
            jdbc.update("UPDATE qc_customer SET status='PENDING' WHERE id=?", customer);
            assertEquals(403, call(HttpMethod.GET, "/products/" + active, null, token).getStatusCodeValue());
            assertEquals(200, call(HttpMethod.GET, "/catalog/products/" + active, null, token).getStatusCodeValue());
        }
    }
}

package com.ruoyi.qicai;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.qicai.payment.WechatPayProperties;

@Service
public class QicaiWechatAuthService
{

    private final JdbcTemplate jdbc;
    private final QicaiAuthService auth;
    private final QicaiWechatCodeService codes;
    private final WechatPayProperties config;
    private final StringRedisTemplate redis;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Value("${qicai.wechat-login.enabled:false}")
    private boolean enabled;

    private QicaiSalesService sales;
    @org.springframework.beans.factory.annotation.Autowired public void setSales(QicaiSalesService value){sales=value;}

    public QicaiWechatAuthService(JdbcTemplate jdbc, QicaiAuthService auth, QicaiWechatCodeService codes,
            WechatPayProperties config, StringRedisTemplate redis, PlatformTransactionManager manager)
    {
        this.jdbc = jdbc; this.auth = auth; this.codes = codes; this.config = config;
        this.redis = redis; this.tx = new TransactionTemplate(manager);
    }

    public Map<String, Object> login(Map<String, Object> body, String remoteAddress)
    {
        only(body, "code", "audience");
        String audience = value(body, "audience", 16);
        if (!"consumer".equals(audience) && !"business".equals(audience))
            throw error(HttpStatus.BAD_REQUEST, "账号类型不正确");
        requireEnabled();
        String code = value(body, "code", 256);
        rateLimit(remoteAddress);
        String openId = codes.exchange(code);
        return tx.execute(state -> account(openId, audience, null));
    }

    public Map<String, Object> registerBusiness(Map<String, Object> body, String remoteAddress)
    {
        requireEnabled();
        only(body, "code", "companyName", "contactName", "phone", "taxId", "address", "salesInviteCode");
        String code = value(body, "code", 256);
        value(body, "companyName", 120); value(body, "contactName", 60); value(body, "address", 255);
        QicaiAuthService.validatePhone(value(body, "phone", 20));
        if (!value(body, "taxId", 32).toUpperCase(java.util.Locale.ROOT).matches("[0-9A-Z]{15,20}"))
            throw error(HttpStatus.BAD_REQUEST, "企业纳税识别码格式不正确");
        rateLimit(remoteAddress);
        String openId = codes.exchange(code);
        return tx.execute(state -> account(openId, "business", body));
    }

    private Map<String, Object> account(String openId, String audience, Map<String, Object> registration)
    {
        // Insert/lock the unique identity before creating a customer. An absent-row
        // SELECT FOR UPDATE alone would allow gap-lock deadlocks and orphan accounts.
        jdbc.update("INSERT INTO qc_wechat_account(app_id,open_id) VALUES(?,?) ON DUPLICATE KEY UPDATE open_id=VALUES(open_id)",
                config.getAppId(), openId);
        Map<String, Object> mapping = jdbc.queryForMap(
                "SELECT consumer_customer_id,business_customer_id FROM qc_wechat_account WHERE app_id=? AND open_id=? FOR UPDATE",
                config.getAppId(), openId);
        String column = "consumer".equals(audience) ? "consumer_customer_id" : "business_customer_id";
        Number existing = (Number) mapping.get(column);
        long id;
        if (existing != null)
        {
            id = existing.longValue();
            List<Map<String, Object>> customers = jdbc.queryForList("SELECT audience,auth_method,status FROM qc_customer WHERE id=? AND deleted=0 FOR UPDATE", id);
            if (customers.isEmpty()) throw error(HttpStatus.FORBIDDEN, "账号当前不可登录");
            Map<String, Object> customer = customers.get(0);
            if (!audience.equals(customer.get("audience")) || !"WECHAT".equals(customer.get("auth_method"))
                    || !("ACTIVE".equals(customer.get("status")) || "PENDING".equals(customer.get("status"))))
                throw error(HttpStatus.FORBIDDEN, "账号当前不可登录");
            if (registration != null) throw error(HttpStatus.CONFLICT, "企业申请已存在，请登录查看审核状态");
        }
        else
        {
            if ("business".equals(audience) && registration == null)
                throw error(HttpStatus.NOT_FOUND, "请先提交企业申请");
            if (registration == null)
                jdbc.update("INSERT INTO qc_customer(audience,auth_method,status) VALUES('consumer','WECHAT','ACTIVE')");
            else
                jdbc.update("INSERT INTO qc_customer(audience,auth_method,company_name,contact_name,contact_phone,tax_id,address,status) VALUES('business','WECHAT',?,?,?,?,?,'PENDING')",
                        value(registration, "companyName", 120), value(registration, "contactName", 60), value(registration, "phone", 20),
                        value(registration, "taxId", 32).toUpperCase(java.util.Locale.ROOT), value(registration, "address", 255));
            id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            if(registration!=null && sales!=null)sales.bindRegistration(id,audience,registration);
            jdbc.update("UPDATE qc_wechat_account SET " + column + "=? WHERE app_id=? AND open_id=?", id, config.getAppId(), openId);
        }
        List<String> identities = jdbc.queryForList("SELECT open_id FROM qc_wechat_identity WHERE customer_id=? AND app_id=?", String.class, id, config.getAppId());
        if (identities.isEmpty())
            jdbc.update("INSERT INTO qc_wechat_identity(customer_id,app_id,open_id) VALUES(?,?,?)", id, config.getAppId(), openId);
        else if (!openId.equals(identities.get(0)))
            throw error(HttpStatus.CONFLICT, "当前账号微信身份不一致，请联系运营");
        return auth.loginResult("customer", id, audience, auth.customerProfile(id));
    }

    private void rateLimit(String remoteAddress)
    {
        try
        {
            Long count = redis.execute(new DefaultRedisScript<Long>(
                    "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n", Long.class),
                    java.util.Collections.singletonList("qc:wechat:ip:" + QicaiAuthService.sha256(remoteAddress == null ? "unknown" : remoteAddress)));
            if (count == null) throw error(HttpStatus.SERVICE_UNAVAILABLE, "微信登录暂不可用");
            if (count > 60) throw error(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后重试");
        }
        catch (org.springframework.dao.DataAccessException ex) { throw error(HttpStatus.SERVICE_UNAVAILABLE, "微信登录暂不可用"); }
    }

    private void requireEnabled()
    {
        if (!enabled) throw error(HttpStatus.SERVICE_UNAVAILABLE, "微信登录暂未开放");
    }

    private static String value(Map<String, Object> body, String key, int max)
    {
        if (!(body.get(key) instanceof String)) throw error(HttpStatus.BAD_REQUEST, key + "格式不正确");
        return QicaiAuthService.required(body, key, max);
    }

    private static void only(Map<String, Object> body, String... fields)
    {
        Set<String> allowed = new HashSet<String>(Arrays.asList(fields));
        if (body == null || !allowed.containsAll(body.keySet())) throw error(HttpStatus.BAD_REQUEST, "请求包含不支持的字段");
    }

    private static QicaiApiException error(HttpStatus status, String message) { return new QicaiApiException(status, message); }
}

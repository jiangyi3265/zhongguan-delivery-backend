package com.ruoyi.qicai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QicaiAuthService
{
    public static final String TOKEN_HEADER = "X-Qicai-Token";
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern TAX_ID = Pattern.compile("^[0-9A-Z]{15,20}$");
    private static final SecureRandom RANDOM = new SecureRandom();


    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder;
    private final QicaiSmsVerifier smsVerifier;
    private final org.springframework.data.redis.core.StringRedisTemplate loginAttempts;

    @Value("${qicai.mobile-session-hours:12}")
    private int sessionHours;

    @Value("${qicai.sms.dev-mode:false}")
    private boolean smsDevMode;

    private QicaiSalesService sales;
    @org.springframework.beans.factory.annotation.Autowired public void setSales(QicaiSalesService value){sales=value;}

    public QicaiAuthService(JdbcTemplate jdbc, BCryptPasswordEncoder passwordEncoder, QicaiSmsVerifier smsVerifier,
            org.springframework.data.redis.core.StringRedisTemplate loginAttempts)
    {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.smsVerifier = smsVerifier;
        this.loginAttempts = loginAttempts;
    }

    @Transactional
    public Map<String, Object> requestSms(Map<String, Object> body, String ip)
    {
        String phone = required(body, "phone", 20);
        String audience = audience(body);
        validatePhone(phone);
        Integer recent = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qc_sms_code WHERE phone=? AND audience=? AND created_at>DATE_SUB(NOW(), INTERVAL 60 SECOND)",
                Integer.class, phone, audience);
        if (recent != null && recent > 0)
        {
            throw new QicaiApiException(HttpStatus.TOO_MANY_REQUESTS, "验证码发送过于频繁，请稍后再试");
        }
        if (!smsDevMode)
        {
            throw new QicaiApiException(HttpStatus.SERVICE_UNAVAILABLE, "短信服务尚未配置");
        }
        String code = String.format("%06d", RANDOM.nextInt(1000000));
        jdbc.update("INSERT INTO qc_sms_code(phone,audience,code_hash,expires_at,requested_ip) VALUES(?,?,?,DATE_ADD(NOW(), INTERVAL 5 MINUTE),?)",
                phone, audience, sha256(code), ip);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("expiresIn", 300);
        result.put("devCode", code);
        return result;
    }

    @Transactional
    public Map<String, Object> registerCustomer(Map<String, Object> body)
    {
        String audience = audience(body);
        String phone = required(body, "phone", 20);
        String password = required(body, "password", 64);
        String contactName = required(body, "contactName", 60);
        String smsCode = required(body, "smsCode", 6);
        validatePhone(phone);
        validatePassword(password);
        verifySms(phone, audience, smsCode);

        String companyName = nullable(body, "companyName", 120);
        String taxId = nullable(body, "taxId", 32);
        String address = nullable(body, "address", 255);
        String status;
        if ("business".equals(audience))
        {
            if (companyName == null || taxId == null)
            {
                throw new QicaiApiException(HttpStatus.BAD_REQUEST, "企业名称和企业纳税识别码不能为空");
            }
            taxId = taxId.toUpperCase();
            if (!TAX_ID.matcher(taxId).matches())
            {
                throw new QicaiApiException(HttpStatus.BAD_REQUEST, "企业纳税识别码格式不正确");
            }
            status = "PENDING";
        }
        else
        {
            if (address == null)
            {
                throw new QicaiApiException(HttpStatus.BAD_REQUEST, "住址不能为空");
            }
            status = "ACTIVE";
        }

        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qc_customer WHERE audience=? AND phone=? AND deleted=0", Integer.class,
                audience, phone);
        if (exists != null && exists > 0)
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "该手机号已注册当前类型账号");
        }
        jdbc.update("INSERT INTO qc_customer(audience,phone,password_hash,company_name,contact_name,tax_id,address,status) VALUES(?,?,?,?,?,?,?,?)",
                audience, phone, passwordEncoder.encode(password), companyName, contactName, taxId, address, status);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if(sales!=null)sales.bindRegistration(id.longValue(),audience,body);
        return customerProfile(id.longValue());
    }

    @Transactional
    public Map<String, Object> loginCustomer(Map<String, Object> body)
    {
        String audience = audience(body);
        String phone = required(body, "phone", 20);
        String password = required(body, "password", 64);
        String attemptKey = "qc:login:customer:" + audience + ":" + sha256(phone);
        checkLoginAttempts(attemptKey);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,password_hash,status FROM qc_customer WHERE audience=? AND phone=? AND auth_method='PASSWORD' AND deleted=0 LIMIT 1 FOR UPDATE",
                audience, phone);
        if (rows.isEmpty() || !passwordEncoder.matches(password, String.valueOf(rows.get(0).get("password_hash"))))
        {
            failedLogin(attemptKey);
            throw new QicaiApiException(HttpStatus.UNAUTHORIZED, "手机号或密码错误");
        }
        String status = String.valueOf(rows.get(0).get("status"));
        if ("DISABLED".equals(status) || "REJECTED".equals(status))
        {
            throw new QicaiApiException(HttpStatus.FORBIDDEN, "账号当前不可登录");
        }
        long id = ((Number) rows.get(0).get("id")).longValue();
        loginAttempts.delete(attemptKey);
        return loginResult("customer", id, audience, customerProfile(id));
    }

    @Transactional
    public Map<String, Object> loginCourier(Map<String, Object> body)
    {
        String phone = required(body, "phone", 20);
        String password = required(body, "password", 64);
        String attemptKey = "qc:login:courier:" + sha256(phone);
        checkLoginAttempts(attemptKey);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,password_hash,name,vehicle_no,status,available FROM qc_courier WHERE phone=? AND deleted=0 LIMIT 1 FOR UPDATE", phone);
        if (rows.isEmpty() || !passwordEncoder.matches(password, String.valueOf(rows.get(0).get("password_hash"))))
        {
            failedLogin(attemptKey);
            throw new QicaiApiException(HttpStatus.UNAUTHORIZED, "配送员账号或密码错误");
        }
        Map<String, Object> courier = rows.get(0);
        if (!"ACTIVE".equals(String.valueOf(courier.get("status"))))
        {
            throw new QicaiApiException(HttpStatus.FORBIDDEN, "配送员账号已停用");
        }
        long id = ((Number) courier.get("id")).longValue();
        courier.remove("password_hash");
        courier.put("available", courierAvailable(courier.get("available")));
        loginAttempts.delete(attemptKey);
        return loginResult("courier", id, "courier", courier);
    }

    /** JDBC TINYINT mappings vary by driver; the mobile API always returns a boolean. */
    static boolean courierAvailable(Object raw)
    {
        if (raw instanceof Boolean) return ((Boolean) raw).booleanValue();
        if (raw instanceof Number)
        {
            try
            {
                java.math.BigDecimal value = new java.math.BigDecimal(raw.toString());
                if (value.compareTo(java.math.BigDecimal.ZERO) == 0) return false;
                if (value.compareTo(java.math.BigDecimal.ONE) == 0) return true;
            }
            catch (NumberFormatException ignored) { /* Invalid persisted state is rejected below. */ }
        }
        throw new QicaiApiException(HttpStatus.INTERNAL_SERVER_ERROR, "配送员接单状态数据异常，请联系管理员");
    }

    @Transactional
    public void logout(HttpServletRequest request)
    {
        String token = request.getHeader(TOKEN_HEADER);
        if (token != null && !token.trim().isEmpty())
        {
            jdbc.update("DELETE FROM qc_session WHERE token_hash=?", sha256(token.trim()));
        }
    }

    @Transactional
    public Principal requireCustomer(HttpServletRequest request)
    {
        return require(request, "customer");
    }

    @Transactional
    public Principal requireCourier(HttpServletRequest request)
    {
        return require(request, "courier");
    }

    @Transactional
    public Principal requireAny(HttpServletRequest request)
    {
        return require(request, null);
    }

    @Transactional
    public Map<String, Object> updateCustomer(long customerId, Map<String, Object> body)
    {
        Map<String, Object> existing = customerProfile(customerId);
        String audience = String.valueOf(existing.get("audience"));
        String phoneColumn = "WECHAT".equals(existing.get("authMethod")) ? "contact_phone" : "phone";
        String contactName = required(body, "contactName", 60);
        String phone = required(body, "phone", 20);
        validatePhone(phone);
        int version = number(body, "version", -1);
        if (version < 0)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "缺少资料版本号，请刷新后重试");
        }
        int changed;
        if ("business".equals(audience))
        {
            String companyName = required(body, "companyName", 120);
            String taxId = required(body, "taxId", 32).toUpperCase();
            if (!TAX_ID.matcher(taxId).matches())
            {
                throw new QicaiApiException(HttpStatus.BAD_REQUEST, "企业纳税识别码格式不正确");
            }
            boolean identityChanged = !companyName.equals(existing.get("companyName")) || !taxId.equals(existing.get("taxId"));
            String status = identityChanged ? "PENDING" : String.valueOf(existing.get("status"));
            changed = jdbc.update("UPDATE qc_customer SET company_name=?," + phoneColumn + "=?,contact_name=?,tax_id=?,status=?,version=version+1 WHERE id=? AND version=? AND deleted=0",
                    companyName, phone, contactName, taxId, status, customerId, version);
        }
        else
        {
            String address = required(body, "address", 255);
            changed = jdbc.update("UPDATE qc_customer SET address=?," + phoneColumn + "=?,contact_name=?,version=version+1 WHERE id=? AND version=? AND deleted=0",
                    address, phone, contactName, customerId, version);
        }
        if (changed == 0)
        {
            throw new QicaiApiException(HttpStatus.CONFLICT, "资料已在其他位置更新，请刷新后重试");
        }
        return customerProfile(customerId);
    }

    public Map<String, Object> customerProfile(long customerId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,audience,COALESCE(contact_phone,phone) AS phone,auth_method AS authMethod,company_name AS companyName,contact_name AS contactName,tax_id AS taxId,address,status,version,created_at AS createdAt,updated_at AS updatedAt FROM qc_customer WHERE id=? AND deleted=0",
                customerId);
        if (rows.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.NOT_FOUND, "客户不存在");
        }
        return rows.get(0);
    }

    private Principal require(HttpServletRequest request, String expectedType)
    {
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.trim().isEmpty())
        {
            throw new QicaiApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT principal_type,principal_id,audience,expires_at FROM qc_session WHERE token_hash=? LIMIT 1",
                sha256(token.trim()));
        if (rows.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.UNAUTHORIZED, "登录状态无效");
        }
        Map<String, Object> row = rows.get(0);
        Object expiresValue = row.get("expires_at");
        LocalDateTime expires = expiresValue instanceof Timestamp
                ? ((Timestamp) expiresValue).toLocalDateTime()
                : (LocalDateTime) expiresValue;
        if (expires.isBefore(LocalDateTime.now()))
        {
            jdbc.update("DELETE FROM qc_session WHERE token_hash=?", sha256(token.trim()));
            throw new QicaiApiException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        String type = String.valueOf(row.get("principal_type"));
        if (expectedType != null && !expectedType.equals(type))
        {
            throw new QicaiApiException(HttpStatus.FORBIDDEN, "当前账号无权访问此功能");
        }
        String accountSql = "courier".equals(type)
                ? "SELECT COUNT(*) FROM qc_courier WHERE id=? AND deleted=0 AND status='ACTIVE'"
                : "SELECT COUNT(*) FROM qc_customer WHERE id=? AND deleted=0 AND status IN ('ACTIVE','PENDING')";
        Integer active = jdbc.queryForObject(accountSql, Integer.class, row.get("principal_id"));
        if (active == null || active == 0)
            throw new QicaiApiException(HttpStatus.UNAUTHORIZED, "账号已停用，请联系运营");
        jdbc.update("UPDATE qc_session SET last_used_at=NOW() WHERE token_hash=?", sha256(token.trim()));
        return new Principal(type, ((Number) row.get("principal_id")).longValue(), String.valueOf(row.get("audience")));
    }

    Map<String, Object> loginResult(String type, long id, String audience, Map<String, Object> profile)
    {
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        // An empty range DELETE takes an InnoDB gap lock. Concurrent logins for
        // different new accounts can then deadlock when inserting their sessions.
        // Find expired keys without locking a range and delete only known rows.
        List<String> expired = jdbc.queryForList(
                "SELECT token_hash FROM qc_session WHERE principal_type=? AND principal_id=? AND expires_at<NOW() ORDER BY token_hash",
                String.class, type, id);
        for (String expiredToken : expired)
        {
            jdbc.update("DELETE FROM qc_session WHERE token_hash=? AND expires_at<NOW()", expiredToken);
        }
        jdbc.update("INSERT INTO qc_session(token_hash,principal_type,principal_id,audience,expires_at) VALUES(?,?,?,?,DATE_ADD(NOW(), INTERVAL ? HOUR))",
                sha256(token), type, id, audience, sessionHours);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("token", token);
        result.put("expiresIn", sessionHours * 3600);
        result.put("profile", profile);
        return result;
    }

    private void verifySms(String phone, String audience, String code)
    {
        long id = smsVerifier.verify(phone, audience, sha256(code));
        if (jdbc.update("UPDATE qc_sms_code SET consumed=1 WHERE id=? AND consumed=0 AND expires_at>NOW()", id) != 1)
            throw new QicaiApiException(HttpStatus.CONFLICT, "验证码已使用或过期，请重新获取");
    }

    public void recordUpload(Principal principal, String fileName)
    {
        jdbc.update("INSERT INTO qc_upload(file_name,principal_type,principal_id) VALUES(?,?,?)", fileName, principal.getType(), principal.getId());
    }

    public String requireUploadedImage(Principal principal, Map<String, Object> body, String key)
    {
        String value = required(body, key, 500);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM qc_upload WHERE file_name=? AND principal_type=? AND principal_id=?",
                Integer.class, value, principal.getType(), principal.getId());
        if (count == null || count == 0) throw new QicaiApiException(HttpStatus.BAD_REQUEST, "请使用当前账号实际上传的图片凭证");
        return value;
    }

    public List<String> requireUploadedImages(Principal principal, Map<String, Object> body, String listKey, String singleKey)
    {
        Object raw = body.get(listKey);
        if (raw == null) return java.util.Collections.singletonList(requireUploadedImage(principal, body, singleKey));
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty() || ((List<?>) raw).size() > 3)
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "请上传1至3张凭证图片");
        List<String> result = new java.util.ArrayList<String>();
        for (Object item : (List<?>) raw)
        {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put(singleKey, item);
            String image = requireUploadedImage(principal, value, singleKey);
            if (!result.contains(image)) result.add(image);
        }
        return result;
    }

    private String audience(Map<String, Object> body)
    {
        String value = required(body, "audience", 16);
        if (!"business".equals(value) && !"consumer".equals(value))
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "账号类型不正确");
        }
        return value;
    }

    public static void validatePhone(String phone)
    {
        if (!PHONE.matcher(phone).matches())
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "请输入正确的11位手机号");
        }
    }

    public static void validatePassword(String password)
    {
        if (password.length() < 8 || password.length() > 32 || password.getBytes(StandardCharsets.UTF_8).length > 72)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "密码长度必须为8至32位");
        }
    }

    private void checkLoginAttempts(String key)
    {
        String count = loginAttempts.opsForValue().get(key);
        if (count != null && Integer.parseInt(count) >= 5)
            throw new QicaiApiException(HttpStatus.TOO_MANY_REQUESTS, "密码错误次数过多，请10分钟后重试");
    }

    private void failedLogin(String key)
    {
        org.springframework.data.redis.core.script.DefaultRedisScript<Long> script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],600) end; return n", Long.class);
        loginAttempts.execute(script, java.util.Collections.singletonList(key));
    }

    public static String required(Map<String, Object> body, String key, int maxLength)
    {
        String value = nullable(body, key, maxLength);
        if (value == null)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, key + "不能为空");
        }
        return value;
    }

    public static String nullable(Map<String, Object> body, String key, int maxLength)
    {
        Object raw = body.get(key);
        if (raw == null)
        {
            return null;
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty())
        {
            return null;
        }
        if (value.length() > maxLength)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, key + "长度不能超过" + maxLength + "个字符");
        }
        return value;
    }

    public static int number(Map<String, Object> body, String key, int defaultValue)
    {
        Object raw = body.get(key);
        if (raw == null)
        {
            return defaultValue;
        }
        try
        {
            return Integer.parseInt(String.valueOf(raw));
        }
        catch (NumberFormatException ex)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, key + "必须为整数");
        }
    }

    static String sha256(String value)
    {
        try
        {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes)
            {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        }
        catch (NoSuchAlgorithmException ex)
        {
            throw new IllegalStateException(ex);
        }
    }

    public static class Principal
    {
        private final String type;
        private final long id;
        private final String audience;

        public Principal(String type, long id, String audience)
        {
            this.type = type;
            this.id = id;
            this.audience = audience;
        }

        public String getType() { return type; }
        public long getId() { return id; }
        public String getAudience() { return audience; }
    }
}

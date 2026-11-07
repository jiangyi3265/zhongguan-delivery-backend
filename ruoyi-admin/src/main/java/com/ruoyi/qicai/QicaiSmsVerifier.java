package com.ruoyi.qicai;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QicaiSmsVerifier
{
    private final JdbcTemplate jdbc;
    public QicaiSmsVerifier(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Failed attempts must survive a rollback of the outer registration transaction.
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = QicaiApiException.class)
    public long verify(String phone, String audience, String codeHash)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,code_hash,attempts FROM qc_sms_code WHERE phone=? AND audience=? AND consumed=0 AND expires_at>NOW() ORDER BY id DESC LIMIT 1 FOR UPDATE", phone, audience);
        if (rows.isEmpty()) throw new QicaiApiException(HttpStatus.BAD_REQUEST, "验证码不存在或已过期");
        Map<String, Object> row = rows.get(0);
        long id = ((Number) row.get("id")).longValue();
        if (((Number) row.get("attempts")).intValue() >= 5)
            throw new QicaiApiException(HttpStatus.TOO_MANY_REQUESTS, "验证码错误次数过多，请重新获取");
        if (!java.security.MessageDigest.isEqual(codeHash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                String.valueOf(row.get("code_hash")).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        {
            jdbc.update("UPDATE qc_sms_code SET attempts=attempts+1 WHERE id=?", id);
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "验证码错误");
        }
        return id;
    }
}

package com.ruoyi.qicai;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QicaiPrivacyService {
    public static final String VERSION="2026-10-27.1";
    private final JdbcTemplate jdbc;
    public QicaiPrivacyService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional
    public void record(QicaiAuthService.Principal principal,String route) {
        String type=principal==null?"visitor":principal.getType();long id=principal==null?0:principal.getId();
        if(principal!=null && jdbc.queryForObject("SELECT COUNT(*) FROM qc_business_audit WHERE action='PRIVACY_CONSENT' AND actor_type=? AND actor_id=? AND JSON_UNQUOTE(JSON_EXTRACT(after_values,'$.version'))=?",Long.class,type,id,VERSION)>0) return;
        Map<String,Object> value=new LinkedHashMap<>();value.put("version",VERSION);value.put("accepted",true);value.put("route",route);
        QicaiBusinessAudit.record(jdbc,type,id,"user-consent","PRIVACY_CONSENT","privacy",id,null,value,null);
    }
}

package com.ruoyi.qicai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.utils.SecurityUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Missing configuration keeps optional storefront entries hidden. */
@Service
public class QicaiStoreSettings {
    private static final int ID=20261027;
    private static final String KEY="qicai.business.entry.visible";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json=new ObjectMapper();
    public QicaiStoreSettings(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Map<String,Object> read() {
        List<String> rows=jdbc.queryForList("SELECT config_value FROM sys_config WHERE config_id=? AND config_key=?",String.class,ID,KEY);
        Map<String,Object> value=new LinkedHashMap<>();value.put("businessEntryVisible",false);value.put("courierEntryVisible",false);value.put("version",0);
        if(rows.isEmpty()) return value;
        try {
            com.fasterxml.jackson.databind.JsonNode stored=json.readTree(rows.get(0));
            if(!stored.path("businessEntryVisible").isBoolean() || !stored.path("version").isInt() || stored.path("version").asInt()<0) throw new IllegalArgumentException();
            // Existing installations have only the enterprise flag. Do not alter it.
            if(stored.has("courierEntryVisible") && !stored.path("courierEntryVisible").isBoolean()) throw new IllegalArgumentException();
            value.put("courierEntryVisible",stored.path("courierEntryVisible").asBoolean(false));
            value.put("businessEntryVisible",stored.path("businessEntryVisible").asBoolean());value.put("version",stored.path("version").asInt());return value;
        } catch(Exception ex) { throw new QicaiApiException(HttpStatus.SERVICE_UNAVAILABLE,"商城开关配置异常，请联系管理员"); }
    }
    public Map<String,Object> features() { Map<String,Object> value=read();value.remove("version");return value; }
    @Transactional
    public Map<String,Object> save(Map<String,Object> body) {
        if(body==null || !(body.get("businessEntryVisible") instanceof Boolean) || !(body.get("version") instanceof Integer)) throw new QicaiApiException(HttpStatus.BAD_REQUEST,"开关参数格式不正确");
        if(body.containsKey("courierEntryVisible") && !(body.get("courierEntryVisible") instanceof Boolean)) throw new QicaiApiException(HttpStatus.BAD_REQUEST,"开关参数格式不正确");
        jdbc.update("INSERT IGNORE INTO sys_config(config_id,config_name,config_key,config_value,config_type,create_by,create_time,remark) VALUES(?,?,?,'{\"businessEntryVisible\":false,\"courierEntryVisible\":false,\"version\":0}','Y',?,NOW(),?)",ID,"小程序入口显示",KEY,SecurityUtils.getUsername(),"控制企业采购与配送员入口按钮显示，保留原有业务功能");
        String key=jdbc.queryForObject("SELECT config_key FROM sys_config WHERE config_id=? FOR UPDATE",String.class,ID);
        if(!KEY.equals(key)) throw new QicaiApiException(HttpStatus.CONFLICT,"配置编号冲突，请联系管理员");
        Map<String,Object> before=read();
        if(!before.get("version").equals(body.get("version"))) throw new QicaiApiException(HttpStatus.CONFLICT,"开关已被修改，请刷新后重试");
        Map<String,Object> after=new LinkedHashMap<>();after.put("businessEntryVisible",body.get("businessEntryVisible"));after.put("courierEntryVisible",body.getOrDefault("courierEntryVisible",before.get("courierEntryVisible")));after.put("version",Math.addExact((Integer)before.get("version"),1));
        try { jdbc.update("UPDATE sys_config SET config_value=?,update_by=?,update_time=NOW() WHERE config_id=? AND config_key=?",json.writeValueAsString(after),SecurityUtils.getUsername(),ID,KEY); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
        QicaiBusinessAudit.record(jdbc,"admin",SecurityUtils.getUserId(),SecurityUtils.getUsername(),"UPDATE_STORE_SETTINGS","storeSettings",ID,before,after,null);
        return after;
    }
}

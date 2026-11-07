package com.ruoyi.qicai;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Managed delivery destinations. Order snapshots never follow later warehouse edits. */
@Service
public class QicaiWarehouseService {
    private final JdbcTemplate jdbc;
    public QicaiWarehouseService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String SELECT="SELECT id,name,address,latitude,longitude,opening_hours AS openingHours,sort_order AS sortOrder,status,version FROM qc_forward_warehouse";
    private static QicaiApiException bad(String m) { return new QicaiApiException(HttpStatus.BAD_REQUEST,m); }
    public List<Map<String,Object>> list(boolean admin) {
        return jdbc.queryForList(SELECT+(admin?"":" WHERE status='ACTIVE'")+" ORDER BY sort_order,id LIMIT 200");
    }
    private String text(Map<String,Object> body,String key,int limit,boolean required) {
        Object raw=body.get(key); if(raw!=null && !(raw instanceof String)) throw bad("仓点信息格式错误");
        String s=raw==null?"":((String)raw).trim();
        if(s.length()>limit || required && s.isEmpty()) throw bad("请完整填写仓点名称、地址及有效信息");return s;
    }
    private long integer(Object raw,long max) {
        try {long n=new BigDecimal(String.valueOf(raw)).longValueExact();if(n<0 || n>max)throw new ArithmeticException();return n;}
        catch(RuntimeException ex){throw bad("仓点编号、排序或版本无效");}
    }
    private BigDecimal coordinate(Object raw,int limit) {
        try {BigDecimal n=new BigDecimal(String.valueOf(raw));if(n.abs().compareTo(BigDecimal.valueOf(limit))>0)throw new ArithmeticException();return n.setScale(7,java.math.RoundingMode.UNNECESSARY);}
        catch(RuntimeException ex){throw bad("请选择有效地图位置，经纬度最多7位小数");}
    }
    @Transactional public Map<String,Object> save(Long id,Map<String,Object> body) {
        String name=text(body,"name",80,true),address=text(body,"address",255,true),hours=text(body,"openingHours",120,false),status=text(body,"status",16,true);
        if(!Arrays.asList("ACTIVE","INACTIVE").contains(status))throw bad("仓点状态无效");
        if(!"GCJ-02".equals(body.get("coordinateSystem")))throw bad("请选择微信地图坐标（GCJ-02）");
        BigDecimal lat=coordinate(body.get("latitude"),90),lng=coordinate(body.get("longitude"),180);long sort=integer(body.get("sortOrder"),9999);
        jdbc.queryForList("SELECT version FROM qc_schema_version WHERE version='forward-warehouse-20261101' FOR UPDATE");
        Map<String,Object> before=null;
        if(id==null) {
            if(jdbc.queryForObject("SELECT COUNT(*) FROM qc_forward_warehouse",Long.class)>=200)throw bad("最多维护200个前置仓，可编辑或停用已有仓点");
            jdbc.update("INSERT INTO qc_forward_warehouse(name,address,latitude,longitude,opening_hours,sort_order,status) VALUES(?,?,?,?,?,?,?)",name,address,lat,lng,hours,sort,status);
            id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        } else {
            before=QicaiBusinessAudit.snapshot(jdbc,"forwardWarehouse",id);
            if(jdbc.update("UPDATE qc_forward_warehouse SET name=?,address=?,latitude=?,longitude=?,opening_hours=?,sort_order=?,status=?,version=version+1 WHERE id=? AND version=?",name,address,lat,lng,hours,sort,status,id,integer(body.get("version"),Integer.MAX_VALUE-1))!=1)
                throw new QicaiApiException(HttpStatus.CONFLICT,"仓点已修改或不存在，请刷新后重试");
        }
        QicaiBusinessAudit.admin(jdbc,before==null?"CREATE_FORWARD_WAREHOUSE":"UPDATE_FORWARD_WAREHOUSE","forwardWarehouse",id,before);
        return jdbc.queryForMap(SELECT+" WHERE id=?",id);
    }
    // Called inside order creation, after its idempotency lookup. The shared
    // configuration lock makes enabling/disabling points atomic with checkout.
    public Map<String,Object> select(QicaiAuthService.Principal user,Map<String,Object> body) {
        Object raw=body.get("forwardWarehouseId");
        if(!"consumer".equals(user.getAudience())) {if(raw!=null)throw bad("企业订单不使用个人前置仓");return null;}
        jdbc.queryForList("SELECT version FROM qc_schema_version WHERE version='forward-warehouse-20261101' LOCK IN SHARE MODE");
        if(raw==null) {
            if(jdbc.queryForObject("SELECT COUNT(*) FROM qc_forward_warehouse WHERE status='ACTIVE'",Long.class)>0)
                throw new QicaiApiException(HttpStatus.CONFLICT,"请选择收货前置仓后重新提交");
            return null;
        }
        long id=integer(raw,Long.MAX_VALUE);List<Map<String,Object>> rows=jdbc.queryForList(SELECT+" WHERE id=? AND status='ACTIVE' LOCK IN SHARE MODE",id);
        if(rows.isEmpty())throw new QicaiApiException(HttpStatus.CONFLICT,"所选前置仓已停用，请重新选择");
        Map<String,Object> point=rows.get(0);
        if(integer(body.get("forwardWarehouseVersion"),Integer.MAX_VALUE)!=((Number)point.get("version")).longValue())throw new QicaiApiException(HttpStatus.CONFLICT,"前置仓信息已更新，请核对后重新选择");
        return point;
    }
    public void snapshot(long order,Map<String,Object> p) {
        if(p!=null)jdbc.update("INSERT INTO qc_order_warehouse(order_id,warehouse_id,name,address,latitude,longitude,opening_hours,warehouse_version) VALUES(?,?,?,?,?,?,?,?)",order,p.get("id"),p.get("name"),p.get("address"),p.get("latitude"),p.get("longitude"),p.get("openingHours"),p.get("version"));
    }
    public void attach(Map<String,Object> order,long id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT warehouse_id AS id,name,address,latitude,longitude,opening_hours AS openingHours FROM qc_order_warehouse WHERE order_id=?",id);
        if(!rows.isEmpty())order.put("forwardWarehouse",rows.get(0));
    }
}

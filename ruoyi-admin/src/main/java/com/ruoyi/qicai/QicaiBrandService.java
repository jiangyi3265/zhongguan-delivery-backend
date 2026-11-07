package com.ruoyi.qicai;

import java.util.*;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Brand presentation is public; prices remain in the existing audience-scoped product API. */
@Service
public class QicaiBrandService {
    private final JdbcTemplate jdbc;
    public QicaiBrandService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static QicaiApiException invalid(String message) { return new QicaiApiException(HttpStatus.BAD_REQUEST,message); }

    public List<Map<String,Object>> list(boolean admin) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,name,description,logo_url AS logoUrl,sort_order AS sortOrder,status,version FROM qc_brand"+(admin?"":" WHERE status='ACTIVE'")+" ORDER BY sort_order,id LIMIT 200");
        List<Map<String,Object>> slides=jdbc.queryForList("SELECT s.brand_id AS brandId,s.image_url AS imageUrl,s.caption FROM qc_brand_slide s JOIN qc_brand b ON b.id=s.brand_id"+(admin?"":" WHERE b.status='ACTIVE'")+" ORDER BY b.sort_order,b.id,s.position");
        for(Map<String,Object> row:rows) {
            List<Map<String,Object>> pictures=new ArrayList<>();
            for(Map<String,Object> slide:slides) if(((Number)slide.get("brandId")).longValue()==((Number)row.get("id")).longValue()) {
                Map<String,Object> picture=new LinkedHashMap<>();picture.put("imageUrl",slide.get("imageUrl"));picture.put("caption",slide.get("caption"));pictures.add(picture);
            }
            row.put("slides",pictures);
            if(!admin) { row.remove("status");row.remove("version");row.remove("sortOrder"); }
        }
        return rows;
    }

    private String text(Map<String,Object> body,String key,int max,boolean required) {
        Object raw=body.get(key);if(raw!=null && !(raw instanceof String)) throw invalid("品牌字段格式不正确");
        String value=raw==null?"":((String)raw).trim();
        if(value.length()>max || (required && value.isEmpty())) throw invalid("请填写有效的品牌"+(key.equals("name")?"名称":"信息"));
        return value;
    }
    private String image(String value) {
        if(value.isEmpty() || value.equals("/static/brands/pps-icon.jpg") || value.equals("/static/brands/pps-intro.jpg")) return value;
        if(!value.matches(java.util.regex.Pattern.quote(QicaiProductMedia.PREFIX)+"[a-f0-9]{64}\\.jpg")) throw invalid("请使用后台上传的静态品牌图片");
        String hash=value.substring(QicaiProductMedia.PREFIX.length(),QicaiProductMedia.PREFIX.length()+64);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM qc_product_image WHERE image_hash=?",Long.class,hash)!=1) throw invalid("品牌图片不存在，请重新上传");
        return value;
    }
    private int integer(Map<String,Object> body,String key,int max) {
        Object value=body.get(key);
        try { int number=new java.math.BigDecimal(String.valueOf(value)).intValueExact();if(number<0 || number>max) throw invalid("品牌排序或版本号无效");return number; }
        catch(NumberFormatException|ArithmeticException ex) { throw invalid("品牌排序或版本号无效"); }
    }
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String,Object> save(Long id,Map<String,Object> body) {
        String name=text(body,"name",60,true),description=text(body,"description",240,false),logo=image(text(body,"logoUrl",180,false));
        String status=text(body,"status",16,true);if(!Arrays.asList("ACTIVE","INACTIVE").contains(status)) throw invalid("品牌状态无效");
        int sort=integer(body,"sortOrder",9999);
        Object raw=body.get("slides");if(!(raw instanceof List) || ((List<?>)raw).size()>4) throw invalid("品牌介绍图最多4张");
        List<Map<String,String>> pictures=new ArrayList<>();
        for(Object item:(List<?>)raw) {
            if(!(item instanceof Map)) throw invalid("品牌介绍图格式无效");
            Map<String,Object> picture=(Map<String,Object>)item;
            String url=image(text(picture,"imageUrl",180,true));
            Map<String,String> checked=new LinkedHashMap<>();checked.put("imageUrl",url);checked.put("caption",text(picture,"caption",80,false));pictures.add(checked);
        }
        Map<String,Object> before=null;
        if(id==null) {
            // Serialize the administrative 200-brand limit across concurrent creates.
            jdbc.queryForList("SELECT version FROM qc_schema_version WHERE version='brands-20261019' FOR UPDATE");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM qc_brand",Long.class)>=200) throw invalid("品牌数量已达上限");
            jdbc.update("INSERT INTO qc_brand(code,name,description,logo_url,sort_order,status) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),name,description,logo,sort,status);
            id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        } else {
            before=QicaiBusinessAudit.snapshot(jdbc,"brand",id);
            int changed=jdbc.update("UPDATE qc_brand SET name=?,description=?,logo_url=?,sort_order=?,status=?,version=version+1 WHERE id=? AND version=?",name,description,logo,sort,status,id,integer(body,"version",Integer.MAX_VALUE-1));
            if(changed!=1) throw new QicaiApiException(HttpStatus.CONFLICT,"品牌已被修改或不存在，请刷新后重试");
        }
        jdbc.update("DELETE FROM qc_brand_slide WHERE brand_id=?",id);
        for(int i=0;i<pictures.size();i++) jdbc.update("INSERT INTO qc_brand_slide(brand_id,position,image_url,caption) VALUES(?,?,?,?)",id,i,pictures.get(i).get("imageUrl"),pictures.get(i).get("caption"));
        QicaiBusinessAudit.admin(jdbc,before==null?"CREATE_BRAND":"UPDATE_BRAND","brand",id,before);
        for(Map<String,Object> row:list(true)) if(((Number)row.get("id")).longValue()==id) return row;
        throw new IllegalStateException("Saved brand missing");
    }
    public void assign(long productId,Map<String,Object> body) {
        if(!body.containsKey("brandId")) return;
        Object raw=body.get("brandId");
        if(raw==null || "".equals(raw)) { jdbc.update("DELETE FROM qc_product_brand WHERE product_id=?",productId);return; }
        long brandId;
        try { brandId=new java.math.BigDecimal(String.valueOf(raw)).longValueExact(); } catch(RuntimeException ex) { throw invalid("品牌编号无效"); }
        if(brandId<=0 || jdbc.queryForList("SELECT id FROM qc_brand WHERE id=? FOR UPDATE",brandId).isEmpty()) throw invalid("所选品牌不存在");
        jdbc.update("INSERT INTO qc_product_brand(product_id,brand_id) VALUES(?,?) ON DUPLICATE KEY UPDATE brand_id=VALUES(brand_id)",productId,brandId);
    }
    @SuppressWarnings("unchecked")
    public Object attach(Object result) {
        List<Map<String,Object>> rows;
        if(result instanceof Map && ((Map<?,?>)result).get("rows") instanceof List) rows=(List<Map<String,Object>>)((Map<?,?>)result).get("rows");
        else rows=Collections.singletonList((Map<String,Object>)result);
        if(rows.isEmpty())return result;
        List<Object> ids=new ArrayList<>();for(Map<String,Object> row:rows)ids.add(row.get("id"));
        List<Map<String,Object>> links=jdbc.queryForList("SELECT p.product_id AS productId,b.id AS brandId,b.name AS brandName FROM qc_product_brand p JOIN qc_brand b ON b.id=p.brand_id WHERE p.product_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray());
        for(Map<String,Object> row:rows)for(Map<String,Object> link:links)if(((Number)row.get("id")).longValue()==((Number)link.get("productId")).longValue()) { row.put("brandId",link.get("brandId"));row.put("brandName",link.get("brandName")); }
        return result;
    }
}

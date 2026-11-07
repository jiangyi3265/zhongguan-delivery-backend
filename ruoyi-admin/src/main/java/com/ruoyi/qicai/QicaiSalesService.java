package com.ruoyi.qicai;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.utils.SecurityUtils;

/** Every sales request rechecks live account/role state and scopes data in SQL. */
@Service
public class QicaiSalesService {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;
    private static final String ACTIVE=" FROM qc_sales_profile s JOIN sys_user u ON u.user_id=s.user_id WHERE u.status='0' AND u.del_flag='0' AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=u.user_id AND r.role_key='qicai_sales' AND r.status='0' AND r.del_flag='0')";
    public QicaiSalesService(JdbcTemplate jdbc, BCryptPasswordEncoder encoder) { this.jdbc=jdbc;this.encoder=encoder; }
    private static QicaiApiException bad(String message) { return new QicaiApiException(HttpStatus.BAD_REQUEST,message); }
    private long ownId() {
        long id=SecurityUtils.getUserId();
        if(jdbc.queryForObject("SELECT COUNT(*)"+ACTIVE+" AND s.user_id=?",Long.class,id)!=1)
            throw new QicaiApiException(HttpStatus.FORBIDDEN,"销售员账号已停用或权限已收回");
        return id;
    }
    public List<Map<String,Object>> accounts() {
        return jdbc.queryForList("SELECT s.user_id AS id,u.user_name AS username,u.nick_name AS name,u.status,s.invite_code AS inviteCode,s.version,(SELECT COUNT(*) FROM qc_customer_sales cs JOIN qc_customer c ON c.id=cs.customer_id WHERE cs.sales_user_id=s.user_id AND c.deleted=0 AND c.audience='business') AS customerCount FROM qc_sales_profile s JOIN sys_user u ON u.user_id=s.user_id WHERE u.del_flag='0' ORDER BY s.user_id DESC");
    }
    @Transactional public Map<String,Object> saveAccount(Long id,Map<String,Object> body) {
        String name=QicaiAuthService.required(body,"name",30);
        String status=QicaiAuthService.required(body,"status",1);
        if(!Arrays.asList("0","1").contains(status))throw bad("销售员状态无效");
        String password=QicaiAuthService.nullable(body,"password",20);
        if(password!=null && password.length()<8)throw bad("密码至少8位");
        if(id==null) {
            String username=QicaiAuthService.required(body,"username",20);
            if(!username.matches("[A-Za-z][A-Za-z0-9_]{3,19}"))throw bad("登录账号需4至20位字母、数字或下划线，以字母开头");
            if(password==null)throw bad("请设置销售员登录密码");
            Long role=jdbc.queryForObject("SELECT role_id FROM sys_role WHERE role_key='qicai_sales' AND status='0' AND del_flag='0' FOR UPDATE",Long.class);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_name=?",Long.class,username)!=0)throw bad("登录账号已存在");
            jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,status,del_flag,create_by,create_time) VALUES(?,?,?,?,'0','qicai',NOW())",username,name,encoder.encode(password),status);
            id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",id,role);
            jdbc.update("INSERT INTO qc_sales_profile(user_id,invite_code) VALUES(?,?)",id,UUID.randomUUID().toString().replace("-",""));
        } else {
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT s.version FROM qc_sales_profile s JOIN sys_user u ON u.user_id=s.user_id WHERE s.user_id=? AND u.del_flag='0' FOR UPDATE",id);
            int version=QicaiAuthService.number(body,"version",-1);
            if(rows.isEmpty() || version<0 || ((Number)rows.get(0).get("version")).intValue()!=version)throw new QicaiApiException(HttpStatus.CONFLICT,"销售员资料已变化，请刷新");
            jdbc.update("UPDATE sys_user SET nick_name=?,status=?,update_time=NOW() WHERE user_id=?",name,status,id);
            if(password!=null)jdbc.update("UPDATE sys_user SET password=? WHERE user_id=?",encoder.encode(password),id);
            jdbc.update("UPDATE qc_sales_profile SET version=version+1 WHERE user_id=?",id);
        }
        Map<String,Object> result=null;
        for(Map<String,Object> row:accounts())if(((Number)row.get("id")).longValue()==id)result=row;
        Map<String,Object> safe=new LinkedHashMap<>(result);safe.remove("inviteCode");
        QicaiBusinessAudit.record(jdbc,"admin",SecurityUtils.getUserId(),SecurityUtils.getUsername(),"SAVE_SALES_ACCOUNT","sales",id,null,safe,null);
        return result;
    }
    /** Called only inside registration transactions. An omitted code keeps admin review. */
    public void bindRegistration(long customerId,String audience,Map<String,Object> body) {
        String code=QicaiAuthService.nullable(body,"salesInviteCode",32);
        if(code==null)return;
        if(!"business".equals(audience) || !code.matches("[a-f0-9]{32}"))throw bad("销售员邀请码无效");
        List<Long> ids=jdbc.queryForList("SELECT s.user_id"+ACTIVE+" AND s.invite_code=? FOR SHARE",Long.class,code);
        if(ids.size()!=1)throw bad("销售员邀请码无效或已停用，请联系管理员");
        jdbc.update("INSERT INTO qc_customer_sales(customer_id,sales_user_id) VALUES(?,?)",customerId,ids.get(0));
    }
    @Transactional public void assign(long customerId,Map<String,Object> body) {
        Map<String,Object> before=QicaiBusinessAudit.snapshot(jdbc,"customer",customerId);
        if(before==null || !"business".equals(before.get("audience")) || ((Number)before.get("deleted")).intValue()!=0)throw bad("只能分配有效的B端客户");
        int version=QicaiAuthService.number(body,"version",-1);
        if(version<0 || version!=((Number)before.get("version")).intValue())throw new QicaiApiException(HttpStatus.CONFLICT,"客户资料已变化，请刷新");
        Long owner=null;
        if(body.get("salesUserId")!=null) {
            try {owner=new java.math.BigDecimal(body.get("salesUserId").toString()).longValueExact();}catch(RuntimeException ex){throw bad("销售员编号无效");}
            if(owner<=0 || jdbc.queryForList("SELECT s.user_id"+ACTIVE+" AND s.user_id=? FOR SHARE",Long.class,owner).size()!=1)throw bad("请选择有效的销售员");
        }
        jdbc.update("DELETE FROM qc_customer_sales WHERE customer_id=?",customerId);
        if(owner!=null)jdbc.update("INSERT INTO qc_customer_sales(customer_id,sales_user_id) VALUES(?,?)",customerId,owner);
        jdbc.update("UPDATE qc_customer SET version=version+1 WHERE id=?",customerId);
        QicaiBusinessAudit.admin(jdbc,"ASSIGN_CUSTOMER_SALES","customer",customerId,before);
    }
    public Map<String,Object> mine(String keyword,int pageNum,int pageSize) {
        long id=ownId();pageNum=Math.max(1,Math.min(pageNum,100000));pageSize=Math.max(1,Math.min(pageSize,100));
        String filter=" FROM qc_customer c JOIN qc_customer_sales cs ON cs.customer_id=c.id WHERE cs.sales_user_id=? AND c.audience='business' AND c.status='PENDING' AND c.deleted=0";
        List<Object> args=new ArrayList<>();args.add(id);
        if(keyword!=null && !keyword.trim().isEmpty()) {filter+=" AND (c.company_name LIKE ? OR c.contact_name LIKE ?)";args.add("%"+keyword.trim()+"%");args.add("%"+keyword.trim()+"%");}
        Map<String,Object> result=new LinkedHashMap<>();result.put("total",jdbc.queryForObject("SELECT COUNT(*)"+filter,Long.class,args.toArray()));
        args.add(pageSize);args.add((pageNum-1)*pageSize);
        result.put("rows",jdbc.queryForList("SELECT c.id,c.company_name AS companyName,c.contact_name AS contactName,COALESCE(c.contact_phone,c.phone) AS phone,c.tax_id AS taxId,c.address,c.version,c.created_at AS createdAt"+filter+" ORDER BY c.id DESC LIMIT ? OFFSET ?",args.toArray()));
        result.put("inviteCode",jdbc.queryForObject("SELECT invite_code FROM qc_sales_profile WHERE user_id=?",String.class,id));
        return result;
    }
    @Transactional public void approve(long customerId,Map<String,Object> body) {
        long id=ownId();
        // Same customer lock as admin review/assignment: ownership cannot change during approval.
        Map<String,Object> before=QicaiBusinessAudit.snapshot(jdbc,"customer",customerId);
        if(before==null || !"business".equals(before.get("audience")) || ((Number)before.get("deleted")).intValue()!=0 || jdbc.queryForObject("SELECT COUNT(*) FROM qc_customer_sales WHERE customer_id=? AND sales_user_id=?",Long.class,customerId,id)!=1)
            throw new QicaiApiException(HttpStatus.NOT_FOUND,"没有可审核的客户申请");
        if(!"PENDING".equals(before.get("status")) || QicaiAuthService.number(body,"version",-1)!=((Number)before.get("version")).intValue())throw new QicaiApiException(HttpStatus.CONFLICT,"申请已处理或资料已变化，请刷新");
        // Only the pending-to-active transition is exposed. No arbitrary target status.
        if(body.keySet().stream().anyMatch(key->!"version".equals(key)))throw bad("仅支持审核通过注册申请");
        jdbc.update("UPDATE qc_customer SET status='ACTIVE',version=version+1 WHERE id=? AND status='PENDING'",customerId);
        QicaiBusinessAudit.record(jdbc,"sales",id,SecurityUtils.getUsername(),"SALES_APPROVE_CUSTOMER","customer",customerId,before,QicaiBusinessAudit.snapshot(jdbc,"customer",customerId),null);
    }
}

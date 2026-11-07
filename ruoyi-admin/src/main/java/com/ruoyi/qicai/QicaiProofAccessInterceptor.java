package com.ruoyi.qicai;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.qicai.QicaiAuthService.Principal;

/** Uploaded business evidence is readable only by its owner, order participants, or an admin. */
@Component
public class QicaiProofAccessInterceptor implements HandlerInterceptor
{
    private final JdbcTemplate jdbc;
    private final QicaiAuthService auth;
    private final PermissionService permissions;
    private final ObjectMapper mapper;

    public QicaiProofAccessInterceptor(JdbcTemplate jdbc, QicaiAuthService auth,
            PermissionService permissions, ObjectMapper mapper)
    {
        this.jdbc = jdbc;
        this.auth = auth;
        this.permissions = permissions;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException
    {
        String file = new UrlPathHelper().getPathWithinApplication(request);
        if (file.equals("/common/download/resource") || file.startsWith("/common/download/resource/"))
        {
            String resource = request.getParameter("resource");
            if (resource == null || !resource.contains("/profile/"))
                return reject(response, HttpStatus.BAD_REQUEST, "资源路径不正确");
            file = "/profile" + FileUtils.stripPrefix(resource);
        }
        // Reject path aliases before the filesystem can normalize them. Case-insensitive
        // comparisons below also protect the same files on Windows filesystems.
        if (!file.startsWith("/profile/") || file.indexOf('\\') >= 0 || file.contains("//")
                || file.indexOf('\0') >= 0 || file.indexOf('%') >= 0 || file.indexOf(':') >= 0 || file.indexOf('~') >= 0)
            return reject(response, HttpStatus.BAD_REQUEST, "资源路径不正确");
        for (String segment : file.split("/"))
        {
            if (".".equals(segment) || "..".equals(segment) || segment.endsWith(".") || segment.endsWith(" "))
                return reject(response, HttpStatus.BAD_REQUEST, "资源路径不正确");
        }
        // Ordinary avatars and exports retain their existing behavior. Business uploads
        // use a dedicated private namespace; legacy upload paths remain protected by DB records.
        String lower = file.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("/profile/upload/") || lower.startsWith("/profile/qicai-proof/")))
            return true;
        try
        {
            List<Map<String, Object>> owners = jdbc.queryForList(
                    "SELECT principal_type,principal_id FROM qc_upload WHERE LOWER(file_name)=LOWER(?)", file);
            String related = " FROM qc_delivery_task d JOIN qc_order o ON o.id=d.order_id WHERE "
                    + "(LOWER(d.pickup_proof_url)=LOWER(?) OR LOWER(d.delivery_proof_url)=LOWER(?) "
                    + "OR EXISTS(SELECT 1 FROM qc_delivery_attachment a WHERE a.delivery_id=d.id AND LOWER(a.file_name)=LOWER(?)))";
            Integer attached = jdbc.queryForObject("SELECT COUNT(*)" + related, Integer.class, file, file, file);
            boolean isProof = lower.startsWith("/profile/qicai-proof/") || !owners.isEmpty() || attached != null && attached > 0;
            if (!isProof) return true;
            response.setHeader("Cache-Control", "private, no-store");
            response.setHeader("Vary", "Authorization, X-Qicai-Token");
            response.setHeader("X-Content-Type-Options", "nosniff");
            Authentication authentication = SecurityUtils.getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof LoginUser && permissions.hasRole("admin"))
                return true;
            Principal principal = auth.requireAny(request);
            for (Map<String, Object> owner : owners)
            {
                if (principal.getType().equals(owner.get("principal_type"))
                        && principal.getId() == ((Number) owner.get("principal_id")).longValue())
                    return true;
            }
            String membership = "customer".equals(principal.getType()) ? "o.customer_id=?" : "d.courier_id=?";
            Integer allowed = jdbc.queryForObject("SELECT COUNT(*)" + related + " AND " + membership,
                    Integer.class, file, file, file, principal.getId());
            if (allowed != null && allowed > 0) return true;
            return reject(response, HttpStatus.NOT_FOUND, "凭证不存在或无权访问");
        }
        catch (QicaiApiException ex)
        {
            return reject(response, ex.getStatus(), ex.getMessage());
        }
        catch (RuntimeException ex)
        {
            // A DB/auth dependency failure must never fall through to public static serving.
            return reject(response, HttpStatus.SERVICE_UNAVAILABLE, "凭证暂时无法读取，请稍后重试");
        }
    }

    private boolean reject(HttpServletResponse response, HttpStatus status, String message) throws IOException
    {
        response.setStatus(status.value());
        response.setHeader("Cache-Control", "private, no-store");
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), AjaxResult.error(status.value(), message));
        return false;
    }
}

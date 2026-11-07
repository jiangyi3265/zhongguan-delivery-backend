package com.ruoyi.framework.web.service;

import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.framework.aspectj.DataScopeAspect;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real JWT parsing and authorization/data-scope code; only persistence is mocked. */
class TokenAuthorizationTest
{
    private static final String SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZg==";
    private static final String SESSION = "synthetic-authorization-session";
    private static final String PASSWORD = "synthetic-encoded-password";
    private final Map<String, Object> cache = new HashMap<>();
    private RedisCache redis;
    private ISysUserService users;
    private ISysMenuService menus;
    private TokenService tokens;
    private SysUser current;
    private MockHttpServletRequest request;

    @BeforeEach void setUp() throws Exception
    {
        redis = mock(RedisCache.class);
        users = mock(ISysUserService.class);
        menus = mock(ISysMenuService.class);
        SysPermissionService permissions = new SysPermissionService();
        ReflectionTestUtils.setField(permissions, "menuService", menus);
        ReflectionTestUtils.setField(permissions, "roleService", mock(ISysRoleService.class));
        tokens = new TokenService();
        ReflectionTestUtils.setField(tokens, "header", "Authorization");
        ReflectionTestUtils.setField(tokens, "secret", SECRET);
        ReflectionTestUtils.setField(tokens, "expireTime", 30);
        ReflectionTestUtils.setField(tokens, "redisCache", redis);
        ReflectionTestUtils.setField(tokens, "userService", users);
        // Allows the unchanged test to prove the pre-fix stale authorization failure.
        if (ReflectionUtils.findField(TokenService.class, "permissionService") != null)
            ReflectionTestUtils.setField(tokens, "permissionService", permissions);
        when(redis.getCacheObject(anyString())).thenAnswer(call -> cache.get(call.getArgument(0)));
        doAnswer(call -> { cache.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(redis).setCacheObject(anyString(), any(), anyInt(), any(TimeUnit.class));
        when(redis.deleteObject(anyString())).thenAnswer(call -> cache.remove(call.getArgument(0)) != null);
        when(users.selectUserById(42L)).thenAnswer(call -> current);
        when(menus.selectMenuPermsByUserId(42L)).thenReturn(Collections.emptySet());
        when(menus.selectMenuPermsByRoleId(anyLong())).thenReturn(Collections.emptySet());
        current = user(101L, role(2L, "operator", "0", "1"));
        cache.put(CacheConstants.LOGIN_TOKEN_KEY + SESSION, staleSession());
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(PASSWORD.getBytes(StandardCharsets.UTF_8));
        StringBuilder fingerprint = new StringBuilder();
        for (byte value : bytes) fingerprint.append(String.format("%02x", value & 0xff));
        cache.put("login_password_version:" + SESSION, fingerprint.toString());
        String jwt = Jwts.builder().claim(Constants.LOGIN_USER_KEY, SESSION)
                .signWith(SignatureAlgorithm.HS512, SECRET).compact();
        request = new MockHttpServletRequest("POST", "/qicai/admin/products");
        request.addHeader("Authorization", "Bearer " + jwt);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach void clearContext()
    {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test void revokedRoleIsNotAuthorizedByOldJwt()
    {
        current.setRoles(Collections.emptyList());
        LoginUser login = authenticate();
        assertFalse(new PermissionService().hasRole("admin"));
        assertTrue(login.getUser().getRoles().isEmpty());
    }

    @Test void disabledAdminRoleCannotAuthorizeOldOrFreshPrincipal()
    {
        current = user(101L, role(1L, "admin", "1", "1"));
        authenticate();
        assertFalse(new PermissionService().hasAnyRoles("admin,operator"));
        LoginUser direct = new LoginUser(42L, 101L, current, Collections.emptySet());
        use(direct);
        assertFalse(new PermissionService().hasRole("admin"));
    }

    @Test void revokedMenuPermissionAlsoStopsDataScopeFromGrantingAllRows()
    {
        LoginUser login = authenticate();
        assertFalse(new PermissionService().hasPermi("system:user:list"));
        assertTrue(login.getPermissions().isEmpty());
        assertTrue(login.getUser().getRoles().get(0).getPermissions().isEmpty());
        assertTrue(scope(login, "system:user:list").contains("d.dept_id = 0"));
    }

    @Test void changedDataScopeAppliesToOldJwtWithoutRelogin()
    {
        current = user(101L, role(2L, "operator", "0", "5"));
        when(menus.selectMenuPermsByRoleId(2L)).thenReturn(set("system:user:list"));
        LoginUser login = authenticate();
        assertTrue(new PermissionService().hasPermi("system:user:list"));
        assertEquals("5", login.getUser().getRoles().get(0).getDataScope());
        assertTrue(scope(login, "system:user:list").contains("u.user_id = 42"));
    }

    @Test void changedDepartmentIsUsedForDepartmentScope()
    {
        current = user(202L, role(2L, "operator", "0", "3"));
        when(menus.selectMenuPermsByRoleId(2L)).thenReturn(set("system:user:list"));
        LoginUser login = authenticate();
        assertEquals(Long.valueOf(202), login.getDeptId());
        assertTrue(scope(login, "system:user:list").contains("d.dept_id = 202"));
        assertFalse(scope(login, "system:user:list").contains("d.dept_id = 101"));
    }

    @Test void staleSlidingRefreshCannotRestoreRevokedAuthorizationOnNextRequest()
    {
        current.setRoles(Collections.emptyList());
        tokens.refreshToken(staleSession());
        authenticate();
        assertFalse(new PermissionService().hasRole("admin"));
        assertFalse(new PermissionService().hasAnyPermi("system:user:list,system:user:edit"));
    }

    @Test void permissionReadFailureMustNotFallBackToCachedWildcard()
    {
        when(menus.selectMenuPermsByRoleId(2L)).thenThrow(new IllegalStateException("synthetic permission store unavailable"));
        assertNull(tokens.getLoginUser(request));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void enabledCurrentRoleStillAuthorizesItsGrantedOperations()
    {
        current = user(101L, role(2L, "admin", "0", "1"));
        when(menus.selectMenuPermsByRoleId(2L)).thenReturn(set("system:user:list"));
        authenticate();
        assertTrue(new PermissionService().hasRole("admin"));
        assertTrue(new PermissionService().hasPermi("system:user:list"));
        assertFalse(new PermissionService().hasPermi("system:user:remove"));
    }

    @Test void disabledUserAndChangedPasswordStillInvalidateTheSession()
    {
        Object originalVersion = cache.get("login_password_version:" + SESSION);
        current.setStatus("1");
        assertNull(tokens.getLoginUser(request));
        assertFalse(cache.containsKey(CacheConstants.LOGIN_TOKEN_KEY + SESSION));
        cache.put(CacheConstants.LOGIN_TOKEN_KEY + SESSION, staleSession());
        cache.put("login_password_version:" + SESSION, originalVersion);
        current.setStatus("0"); current.setPassword("new-synthetic-encoded-password");
        assertNull(tokens.getLoginUser(request));
        assertFalse(cache.containsKey(CacheConstants.LOGIN_TOKEN_KEY + SESSION));
    }

    private LoginUser authenticate()
    {
        LoginUser login = tokens.getLoginUser(request);
        assertNotNull(login);
        use(login);
        return login;
    }

    private void use(LoginUser login)
    {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
    }

    private String scope(LoginUser login, String permission)
    {
        BaseEntity query = new BaseEntity();
        query.getParams().put("dataScope", "");
        JoinPoint point = mock(JoinPoint.class);
        when(point.getArgs()).thenReturn(new Object[] { query });
        DataScopeAspect.dataScopeFilter(point, login.getUser(), "d", "u", permission);
        return String.valueOf(query.getParams().get("dataScope"));
    }

    private LoginUser staleSession()
    {
        SysRole oldRole = role(2L, "admin", "0", "1");
        oldRole.setPermissions(set("system:user:list", "system:user:edit"));
        LoginUser login = new LoginUser(42L, 101L, user(101L, oldRole), set(Constants.ALL_PERMISSION));
        login.setToken(SESSION); login.setLoginTime(0L); login.setExpireTime(0L);
        return login;
    }

    private SysUser user(long department, SysRole... roles)
    {
        SysUser user = new SysUser(); user.setUserId(42L); user.setDeptId(department);
        user.setUserName("synthetic-security-user"); user.setPassword(PASSWORD);
        user.setStatus("0"); user.setDelFlag("0");
        user.setRoles(new ArrayList<>(Arrays.asList(roles)));
        return user;
    }

    private SysRole role(long id, String key, String status, String scope)
    {
        SysRole role = new SysRole(); role.setRoleId(id); role.setRoleKey(key);
        role.setStatus(status); role.setDelFlag("0"); role.setDataScope(scope);
        return role;
    }

    private Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
}

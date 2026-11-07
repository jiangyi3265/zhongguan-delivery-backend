package com.ruoyi.framework.web.service;

import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.user.UserPasswordNotMatchException;
import com.ruoyi.common.exception.user.UserPasswordRetryLimitExceedException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.security.context.AuthenticationContextHolder;
import com.ruoyi.system.service.ISysUserService;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Tests password-service decisions and Redis call contracts, not Lua execution. */
class PasswordAccountLockTest
{
    private static final String PASSWORD = "SyntheticOnly2026!";
    private static final String HASH = SecurityUtils.encryptPassword(PASSWORD);
    private static final String ACCOUNT_KEY = "pwd_account_err_cnt:42";
    private SysPasswordService service;
    private RedisCache legacy;
    private StringRedisTemplate counters;
    private ISysUserService users;
    private SysUser user;

    @BeforeEach void setup()
    {
        service = new SysPasswordService(); legacy = mock(RedisCache.class);
        counters = mock(StringRedisTemplate.class); users = mock(ISysUserService.class);
        ReflectionTestUtils.setField(service, "redisCache", legacy);
        ReflectionTestUtils.setField(service, "maxRetryCount", 5);
        ReflectionTestUtils.setField(service, "lockTime", 10);
        if (ReflectionUtils.findField(SysPasswordService.class, "counterRedis") != null)
            ReflectionTestUtils.setField(service, "counterRedis", counters);
        if (ReflectionUtils.findField(SysPasswordService.class, "userService") != null)
            ReflectionTestUtils.setField(service, "userService", users);
        user = new SysUser(); user.setUserId(42L); user.setUserName("Operator"); user.setPassword(HASH);
        when(users.selectUserByUserName(anyString())).thenReturn(user);
        // Deliberately a response stub: real Redis/Lua concurrency is integration coverage.
        when(counters.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            Object[] args = call.getArguments();
            String mode = (String) args[2];
            return "CHECK".equals(mode) ? Long.parseLong((String) args[5]) : "FAIL".equals(mode) ? 1L : 0L;
        });
    }

    @AfterEach void clear() { AuthenticationContextHolder.clearContext(); }

    private void validate(String alias, String password)
    {
        AuthenticationContextHolder.setContext(new UsernamePasswordAuthenticationToken(alias, password));
        service.validate(user);
    }

    @Test void existingCanonicalLockAlsoBlocksUppercaseAlias()
    {
        when(legacy.getCacheObject(CacheConstants.PWD_ERR_CNT_KEY + "Operator")).thenReturn(5);
        assertThrows(UserPasswordRetryLimitExceedException.class, () -> validate("OPERATOR", PASSWORD));
    }

    @Test void failedAliasesUseTheSameStableAccountCounter()
    {
        assertThrows(UserPasswordNotMatchException.class, () -> validate("Operator", "Wrong2026!"));
        assertThrows(UserPasswordNotMatchException.class, () -> validate("OPERATOR", "Wrong2026!"));
        verify(counters, times(2)).execute(any(RedisScript.class), eq(Collections.singletonList(ACCOUNT_KEY)), eq("FAIL"), eq("5"), eq("600"), eq("0"));
    }

    @Test void renamedAccountRetainsItsExistingStableLock()
    {
        user.setUserName("Renamed");
        when(counters.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(5L);
        assertThrows(UserPasswordRetryLimitExceedException.class, () -> validate("RENAMED", PASSWORD));
    }

    @Test void concurrentLockBeforeHashCompletesRejectsCorrectPassword()
    {
        when(counters.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(0L, -1L);
        assertThrows(UserPasswordRetryLimitExceedException.class, () -> validate("Operator", PASSWORD));
    }

    @Test void successfulPasswordClearsCounterThroughAtomicDecision()
    {
        validate("OPERATOR", PASSWORD);
        verify(counters).execute(any(RedisScript.class), eq(Collections.singletonList(ACCOUNT_KEY)), eq("SUCCESS"), eq("5"), eq("600"), eq("0"));
    }

    @Test void administratorCanUnlockUsingADatabaseEquivalentAlias()
    {
        service.clearLoginRecordCache("OPERATOR");
        verify(users).selectUserByUserName("OPERATOR");
        verify(counters).delete(ACCOUNT_KEY);
        verify(legacy).deleteObject(CacheConstants.PWD_ERR_CNT_KEY + "Operator");
        verify(legacy).deleteObject(CacheConstants.PWD_ERR_CNT_KEY + "OPERATOR");
    }

    @Test void counterFailureDoesNotAuthorizeCorrectPassword()
    {
        when(counters.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("synthetic Redis unavailable"));
        assertEquals("synthetic Redis unavailable", assertThrows(IllegalStateException.class, () -> validate("Operator", PASSWORD)).getMessage());
    }

    @Test void unknownUsernameHasTheSameCredentialErrorAsWrongPassword()
    {
        UserDetailsServiceImpl details = new UserDetailsServiceImpl();
        when(users.selectUserByUserName("Unknown")).thenReturn(null);
        ReflectionTestUtils.setField(details, "userService", users);
        assertThrows(UserPasswordNotMatchException.class, () -> details.loadUserByUsername("Unknown"));
    }

    @Test void legacyUsernameCannotCollideWithAnyStableCounter()
    {
        user.setUserName("account:42");
        validate("account:42", PASSWORD);
        ArgumentCaptor<List> counterKeys = ArgumentCaptor.forClass(List.class);
        verify(counters, times(2)).execute(any(RedisScript.class), counterKeys.capture(), anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<String> deletedLegacy = ArgumentCaptor.forClass(String.class);
        verify(legacy).deleteObject(deletedLegacy.capture());
        for (List keys : counterKeys.getAllValues())
            assertFalse(keys.contains(deletedLegacy.getValue()), "Legacy username cleanup must never erase an account counter");
    }
}

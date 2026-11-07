package com.ruoyi.framework.web.service;

import java.util.Collections;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.user.UserPasswordNotMatchException;
import com.ruoyi.common.exception.user.UserPasswordRetryLimitExceedException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.security.context.AuthenticationContextHolder;
import com.ruoyi.system.service.ISysUserService;

/**
 * 登录密码方法
 * 
 * @author ruoyi
 */
@Component
public class SysPasswordService
{
    @Autowired
    private RedisCache redisCache;

    @Autowired
    private StringRedisTemplate counterRedis;

    @Autowired
    @Lazy
    private ISysUserService userService;

    private static final DefaultRedisScript<Long> ATTEMPT = new DefaultRedisScript<>(
            "local n=tonumber(redis.call('get',KEYS[1]) or '0'); "
            + "local old=tonumber(ARGV[4]); "
            + "if old>n then n=old; redis.call('set',KEYS[1],n,'EX',ARGV[3]); end; "
            + "if ARGV[1]=='CHECK' then return n; end; "
            + "if n>=tonumber(ARGV[2]) then return -1; end; "
            + "if ARGV[1]=='SUCCESS' then redis.call('del',KEYS[1]); return 0; end; "
            + "n=redis.call('incr',KEYS[1]); redis.call('expire',KEYS[1],ARGV[3]); return n;", Long.class);

    @Value(value = "${user.password.maxRetryCount}")
    private int maxRetryCount;

    @Value(value = "${user.password.lockTime}")
    private int lockTime;

    /**
     * 登录账户密码错误次数缓存键名
     * 
     * @param username 用户名
     * @return 缓存键key
     */
    private String getCacheKey(String username)
    {
        return CacheConstants.PWD_ERR_CNT_KEY + username;
    }

    public void validate(SysUser user)
    {
        Authentication usernamePasswordAuthenticationToken = AuthenticationContextHolder.getContext();
        String username = usernamePasswordAuthenticationToken.getName();
        String password = usernamePasswordAuthenticationToken.getCredentials().toString();

        if (user.getUserId() == null) throw new IllegalStateException("登录账户标识缺失");
        // The DB resolves equivalent aliases; do not invent a Java case/accent rule.
        int legacy = Math.max(legacyCount(user.getUserName()), legacyCount(username));
        if (attempt(user, "CHECK", legacy) >= maxRetryCount)
        {
            throw new UserPasswordRetryLimitExceedException(maxRetryCount, lockTime);
        }

        boolean matched = matches(user, password);
        // Recheck inside the atomic decision: another request may lock the account
        // while BCrypt is running. Never clear that lock using a correct late request.
        if (attempt(user, matched ? "SUCCESS" : "FAIL", legacy) < 0)
        {
            throw new UserPasswordRetryLimitExceedException(maxRetryCount, lockTime);
        }
        if (!matched) throw new UserPasswordNotMatchException();
        // Only legacy keys here: deleting the new key again would erase a failure
        // arriving after the successful atomic decision.
        redisCache.deleteObject(getCacheKey(user.getUserName()));
        if (!user.getUserName().equals(username)) redisCache.deleteObject(getCacheKey(username));
    }

    private String accountKey(Long userId)
    {
        // Separate namespace: a legal old username such as "account:42" must
        // never alias another account's stable counter during legacy cleanup.
        return CacheConstants.PWD_ACCOUNT_ERR_CNT_KEY + userId;
    }

    private int legacyCount(String username)
    {
        Number count = redisCache.getCacheObject(getCacheKey(username));
        return count == null ? 0 : Math.max(0, count.intValue());
    }

    private long attempt(SysUser user, String mode, int legacy)
    {
        Long result = counterRedis.execute(ATTEMPT, Collections.singletonList(accountKey(user.getUserId())),
                mode, Integer.toString(maxRetryCount), Long.toString(lockTime * 60L), Integer.toString(legacy));
        if (result == null)
        {
            throw new IllegalStateException("登录安全计数暂不可用");
        }
        return result;
    }

    public boolean matches(SysUser user, String rawPassword)
    {
        return SecurityUtils.matchesPassword(rawPassword, user.getPassword());
    }

    public void clearLoginRecordCache(String loginName)
    {
        SysUser user = userService.selectUserByUserName(loginName);
        if (user != null)
        {
            counterRedis.delete(accountKey(user.getUserId()));
            redisCache.deleteObject(getCacheKey(user.getUserName()));
        }
        if (user == null || !user.getUserName().equals(loginName)) redisCache.deleteObject(getCacheKey(loginName));
    }
}

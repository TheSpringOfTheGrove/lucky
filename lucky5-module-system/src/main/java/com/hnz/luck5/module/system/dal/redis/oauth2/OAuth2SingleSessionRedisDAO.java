package com.hnz.luck5.module.system.dal.redis.oauth2;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hnz.luck5.module.system.dal.redis.RedisKeyConstants.OAUTH2_SINGLE_SESSION;

/** Stores the authoritative refresh token for an admin account's only active login session. */
@Repository
public class OAuth2SingleSessionRedisDAO {

    private static final DefaultRedisScript<Long> DELETE_IF_MATCHES = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public String get(Long tenantId, Integer userType, Long userId) {
        return stringRedisTemplate.opsForValue().get(formatKey(tenantId, userType, userId));
    }

    public void set(Long tenantId, Integer userType, Long userId, String refreshToken,
                    LocalDateTime expiresTime) {
        long ttlSeconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), expiresTime);
        if (ttlSeconds > 0) {
            stringRedisTemplate.opsForValue().set(
                    formatKey(tenantId, userType, userId), refreshToken, ttlSeconds, TimeUnit.SECONDS);
        }
    }

    public void setIfAbsent(Long tenantId, Integer userType, Long userId, String refreshToken,
                            LocalDateTime expiresTime) {
        long ttlSeconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), expiresTime);
        if (ttlSeconds > 0) {
            stringRedisTemplate.opsForValue().setIfAbsent(
                    formatKey(tenantId, userType, userId), refreshToken, ttlSeconds, TimeUnit.SECONDS);
        }
    }

    public void delete(Long tenantId, Integer userType, Long userId) {
        stringRedisTemplate.delete(formatKey(tenantId, userType, userId));
    }

    public void deleteIfMatches(Long tenantId, Integer userType, Long userId, String refreshToken) {
        stringRedisTemplate.execute(DELETE_IF_MATCHES,
                List.of(formatKey(tenantId, userType, userId)), refreshToken);
    }

    private static String formatKey(Long tenantId, Integer userType, Long userId) {
        return String.format(OAUTH2_SINGLE_SESSION, tenantId, userType, userId);
    }
}

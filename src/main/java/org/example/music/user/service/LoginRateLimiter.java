package org.example.music.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.example.music.user.exception.AuthStateStoreUnavailableException;
import org.example.music.user.exception.LoginRateLimitedException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {

    private static final long ACCOUNT_FAILURE_LIMIT = 5;
    private static final long ACCOUNT_FAILURE_TTL_SECONDS = 900;
    private static final long IP_REQUEST_LIMIT = 20;
    private static final DateTimeFormatter MINUTE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final RedisScript<Long> CHECK = new DefaultRedisScript<>(
            """
            local ipCount = redis.call('INCR', KEYS[1])
            if ipCount == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            if ipCount > tonumber(ARGV[2]) then
              return 2
            end
            local failures = redis.call('GET', KEYS[2])
            if failures and tonumber(failures) >= tonumber(ARGV[3]) then
              return 1
            end
            return 0
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public LoginRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public LoginRateLimitDecision consumeRequest(String identity, String clientIp) {
        try {
            Long result = redisTemplate.execute(
                    CHECK,
                    List.of(ipKey(clientIp), failureKey(identity)),
                    "60",
                    String.valueOf(IP_REQUEST_LIMIT),
                    String.valueOf(ACCOUNT_FAILURE_LIMIT)
            );
            if (result == null || result == 0L) {
                return LoginRateLimitDecision.allowedDecision();
            }
            if (result == 1L) {
                return LoginRateLimitDecision.limitedDecision(
                        LoginRateLimitScope.ACCOUNT_FAILURE_LIMIT,
                        retryAfter(failureKey(identity))
                );
            }
            return LoginRateLimitDecision.limitedDecision(
                    LoginRateLimitScope.IP_RATE_LIMIT,
                    retryAfter(ipKey(clientIp))
            );
        } catch (DataAccessException exception) {
            throw new AuthStateStoreUnavailableException("Authentication state store is unavailable", exception);
        }
    }

    public void recordFailure(String identity) {
        try {
            String key = failureKey(identity);
            Long attempts = redisTemplate.opsForValue().increment(key);
            if (attempts != null && attempts == 1L) {
                redisTemplate.expire(key, java.time.Duration.ofSeconds(ACCOUNT_FAILURE_TTL_SECONDS));
            }
        } catch (DataAccessException exception) {
            throw new AuthStateStoreUnavailableException("Authentication state store is unavailable", exception);
        }
    }

    public void resetFailures(String identity) {
        try {
            redisTemplate.delete(failureKey(identity));
        } catch (DataAccessException exception) {
            throw new AuthStateStoreUnavailableException("Authentication state store is unavailable", exception);
        }
    }

    public long retryAfterSeconds(String identity, String clientIp, LoginRateLimitScope scope) {
        String key = scope == LoginRateLimitScope.IP_RATE_LIMIT ? ipKey(clientIp) : failureKey(identity);
        return retryAfter(key);
    }

    private String ipKey(String clientIp) {
        return "music:auth:login:ip:" + digest(clientIp) + ":" + LocalDateTime.now(ZoneOffset.UTC).format(MINUTE_FORMAT);
    }

    private String failureKey(String identity) {
        return "music:auth:login:failures:" + digest(identity);
    }

    private long retryAfter(String key) {
        Long seconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return seconds == null || seconds < 1 ? 1 : seconds;
    }

    private String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
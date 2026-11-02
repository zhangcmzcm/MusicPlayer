package org.example.music.user.service;

import java.util.List;
import org.example.music.user.exception.PasswordChangeRateLimitedException;
import org.example.music.user.exception.PasswordChangeRateLimitUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class PasswordChangeRateLimiter {
    private static final RedisScript<List> CONSUME = new DefaultRedisScript<>("""
            local kind = redis.call('TYPE', KEYS[1]).ok
            if kind == 'none' then
              redis.call('SET', KEYS[1], '1', 'PX', 900000)
              return {0, 0}
            end
            if kind ~= 'string' then return {2, 0} end
            local count = redis.call('GET', KEYS[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if not string.match(count, '^[1-5]$') or ttl <= 0 or ttl > 900000 then
              return {2, 0}
            end
            if tonumber(count) == 5 then return {1, math.ceil(ttl / 1000)} end
            redis.call('INCR', KEYS[1])
            return {0, 0}
            """, List.class);

    private final StringRedisTemplate redis;

    public PasswordChangeRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void consumeOrThrow(Integer userId) {
        final List<?> result;
        try {
            result = redis.execute(CONSUME, List.of("music:auth:password-change:user:" + userId));
        } catch (DataAccessException exception) {
            throw new PasswordChangeRateLimitUnavailableException(exception);
        }
        if (result == null || result.size() != 2 || !(result.get(0) instanceof Long status)
                || !(result.get(1) instanceof Long seconds)) {
            throw new PasswordChangeRateLimitUnavailableException(null);
        }
        if (status == 0 && seconds == 0) {
            return;
        }
        if (status == 1 && seconds >= 1 && seconds <= 900) {
            throw new PasswordChangeRateLimitedException(seconds);
        }
        throw new PasswordChangeRateLimitUnavailableException(null);
    }
}

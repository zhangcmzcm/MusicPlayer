package org.example.music.track.service;

import java.util.List;
import org.example.music.track.config.TrackSearchProperties;
import org.example.music.track.exception.TrackSearchRateLimitedException;
import org.example.music.track.exception.TrackSearchRateLimitUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class TrackSearchRateLimiter {
    static final RedisScript<List> CONSUME = new DefaultRedisScript<>("""
            local kind = redis.call('TYPE', KEYS[1]).ok
            if kind == 'none' then
              redis.call('SET', KEYS[1], '1', 'PX', 60000)
              return {0, 0}
            end
            if kind ~= 'string' then return {2, 0} end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl == -2 then
              redis.call('SET', KEYS[1], '1', 'PX', 60000)
              return {0, 0}
            end
            if ttl < 0 or ttl > 60000 then return {2, 0} end
            local count = redis.call('GET', KEYS[1])
            if not count or #count > 10 or not string.match(count, '^[1-9][0-9]*$') then
              return {2, 0}
            end
            local number = tonumber(count)
            if number > 2147483647 then return {2, 0} end
            if number >= tonumber(ARGV[1]) then
              return {1, math.max(1, math.ceil(ttl / 1000))}
            end
            redis.call('INCR', KEYS[1])
            return {0, 0}
            """, List.class);

    private final StringRedisTemplate redis;
    private final TrackSearchProperties properties;

    public TrackSearchRateLimiter(StringRedisTemplate redis, TrackSearchProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void consumeOrThrow(Integer userId) {
        final List<?> result;
        try {
            result = redis.execute(CONSUME, List.of("music:track-search:user:" + userId),
                    Integer.toString(properties.rateLimitPerMinute()));
        } catch (DataAccessException exception) {
            throw new TrackSearchRateLimitUnavailableException(exception);
        }
        if (result == null || result.size() != 2 || !(result.get(0) instanceof Long status)
                || !(result.get(1) instanceof Long seconds)) {
            throw new TrackSearchRateLimitUnavailableException(null);
        }
        if (status == 0 && seconds == 0) {
            return;
        }
        if (status == 1 && seconds >= 1 && seconds <= 60) {
            throw new TrackSearchRateLimitedException(seconds);
        }
        throw new TrackSearchRateLimitUnavailableException(null);
    }
}

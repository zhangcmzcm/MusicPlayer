package org.example.music.track.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.example.music.track.exception.MediaImportStoreUnavailableException;
import org.example.music.track.exception.TrackImportRateLimitedException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class TrackImportRateLimiter {

    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            if count > tonumber(ARGV[2]) then
              return redis.call('TTL', KEYS[1])
            end
            return 0
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final TrackImportProperties properties;

    public TrackImportRateLimiter(StringRedisTemplate redisTemplate, TrackImportProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void check(int userId) {
        String key = "music:track-import:admin:" + userId + ":" + System.currentTimeMillis() / 60_000L;
        try {
            Long result = redisTemplate.execute(
                    ACQUIRE,
                    List.of(key),
                    "60",
                    String.valueOf(properties.rateLimitPerMinute())
            );
            if (result != null && result > 0) {
                long retryAfter = result;
                if (retryAfter < 1) {
                    Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                    retryAfter = ttl == null || ttl < 1 ? 1 : ttl;
                }
                throw new TrackImportRateLimitedException(retryAfter);
            }
        } catch (DataAccessException exception) {
            throw new MediaImportStoreUnavailableException("Track import rate limit store unavailable", exception);
        }
    }
}

package org.example.music.security;

import org.example.music.user.exception.AuthStateStoreUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class UserTokenVersionService {

    private static final String KEY_PREFIX = "music:auth:user-token-version:";

    private final StringRedisTemplate redisTemplate;

    public UserTokenVersionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public long current(Integer userId) {
        try {
            String value = redisTemplate.opsForValue().get(key(userId));
            return value == null || value.isBlank() ? 0L : Long.parseLong(value);
        } catch (DataAccessException | NumberFormatException exception) {
            throw new AuthStateStoreUnavailableException("Authentication state store is unavailable", exception);
        }
    }

    public long increment(Integer userId) {
        try {
            Long value = redisTemplate.opsForValue().increment(key(userId));
            return value == null ? 0L : value;
        } catch (DataAccessException exception) {
            throw new AuthStateStoreUnavailableException("Authentication state store is unavailable", exception);
        }
    }

    private String key(Integer userId) {
        return KEY_PREFIX + userId;
    }
}
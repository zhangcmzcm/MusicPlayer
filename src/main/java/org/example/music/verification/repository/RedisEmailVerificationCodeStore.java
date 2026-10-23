package org.example.music.verification.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.music.verification.domain.EmailVerificationCodeSnapshot;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisEmailVerificationCodeStore implements EmailVerificationCodeStore {

    private static final String KEY_PREFIX = "music:verification:code:";
    private static final Duration CODE_TTL = Duration.ofSeconds(300);
    private static final RedisScript<Long> SAVE = new DefaultRedisScript<>(
            """
            redis.call('HSET', KEYS[1], 'codeHash', ARGV[1])
            redis.call('HSET', KEYS[1], 'version', ARGV[2])
            redis.call('HSET', KEYS[1], 'attempts', '0')
            redis.call('HSET', KEYS[1], 'usedAt', '')
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """,
            Long.class
    );
    private static final RedisScript<Long> INCREMENT_FAILED_ATTEMPTS = new DefaultRedisScript<>(
            """
            if redis.call('HEXISTS', KEYS[1], 'version') == 0 then
              return -1
            end
            if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then
              return -1
            end
            return redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            """,
            Long.class
    );
    private static final RedisScript<Long> CONSUME = new DefaultRedisScript<>(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return 0
            end
            if redis.call('HGET', KEYS[1], 'usedAt') ~= '' then
              return -1
            end
            if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then
              return -2
            end
            redis.call('HSET', KEYS[1], 'usedAt', ARGV[2])
            return 1
            """,
            Long.class
    );
    private static final RedisScript<Long> REMOVE_IF_VERSION = new DefaultRedisScript<>(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return 0
            end
            if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then
              return -1
            end
            redis.call('DEL', KEYS[1])
            return 1
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public RedisEmailVerificationCodeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<EmailVerificationCodeSnapshot> find(String email, VerificationCodePurpose purpose) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key(email, purpose));
        if (values.isEmpty()) {
            return Optional.empty();
        }

        String codeHash = required(values, "codeHash");
        String version = required(values, "version");
        int attempts = Integer.parseInt((String) values.getOrDefault("attempts", "0"));
        String usedAtValue = (String) values.get("usedAt");
        Instant usedAt = usedAtValue == null || usedAtValue.isBlank() ? null : Instant.parse(usedAtValue);
        return Optional.of(new EmailVerificationCodeSnapshot(codeHash, version, attempts, usedAt));
    }

    @Override
    public Instant save(
            String email,
            VerificationCodePurpose purpose,
            String codeHash,
            String version
    ) {
        Instant expiresAt = Instant.now().plus(CODE_TTL);
        Long result = redisTemplate.execute(
                SAVE,
                List.of(key(email, purpose)),
                codeHash,
                version,
                String.valueOf(CODE_TTL.toSeconds())
        );
        if (result == null || result != 1L) {
            throw new IllegalStateException("Redis verification code write was not acknowledged");
        }
        return expiresAt;
    }

    @Override
    public int incrementFailedAttempts(String email, VerificationCodePurpose purpose, String version) {
        Long attempts = redisTemplate.execute(
                INCREMENT_FAILED_ATTEMPTS,
                List.of(key(email, purpose)),
                version
        );
        return attempts == null ? -1 : attempts.intValue();
    }

    @Override
    public boolean consume(String email, VerificationCodePurpose purpose, String version, Instant usedAt) {
        Long result = redisTemplate.execute(
                CONSUME,
                List.of(key(email, purpose)),
                version,
                usedAt.toString()
        );
        return result != null && result == 1L;
    }

    @Override
    public boolean removeIfVersion(String email, VerificationCodePurpose purpose, String version) {
        Long result = redisTemplate.execute(
                REMOVE_IF_VERSION,
                List.of(key(email, purpose)),
                version
        );
        return result != null && result == 1L;
    }

    private String key(String email, VerificationCodePurpose purpose) {
        return KEY_PREFIX + purpose.name() + ":" + email;
    }

    private String required(Map<Object, Object> values, String field) {
        String value = (String) values.get(field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Redis verification code is missing field " + field);
        }
        return value;
    }
}
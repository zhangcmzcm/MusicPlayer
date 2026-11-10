package org.example.music.user.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.example.music.user.domain.PasswordResetIdentifiers;
import org.example.music.user.exception.PasswordResetConfirmationRateLimitedException;
import org.example.music.user.exception.PasswordResetConfirmationUnavailableException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class PasswordResetCompletionQuotaStore {
    public static final String PREFIX = "music:auth:password-reset-completion:";
    private static final RedisScript<List> IP = script("admit-ip");
    private static final RedisScript<List> ACCOUNT = script("admit-account");
    private final StringRedisTemplate redis;

    public PasswordResetCompletionQuotaStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static RedisScript<List> script(String name) {
        try {
            return new DefaultRedisScript<>(resource("redis/password-reset-confirmation/common.lua") + "\n"
                    + resource("redis/password-reset-completion/" + name + ".lua"), List.class);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError("Password reset completion scripts unavailable");
        }
    }

    private static String resource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public void admitIp(String digest) {
        if (!PasswordResetIdentifiers.isCanonicalDigest(digest)) throw unavailable();
        long wait = admit(IP, PREFIX + "ip:" + digest, 60);
        if (wait > 0) throw new PasswordResetConfirmationRateLimitedException(wait);
    }

    public boolean admitAccount(Integer userId) {
        if (userId == null || userId < 1) throw unavailable();
        return admit(ACCOUNT, PREFIX + "user:" + userId, 900) == 0;
    }

    private long admit(RedisScript<List> script, String key, long maximum) {
        List<?> reply;
        try {
            reply = redis.execute(script, List.of(key));
        } catch (RuntimeException failure) {
            throw new PasswordResetConfirmationUnavailableException(failure);
        }
        if (reply != null && reply.size() == 2 && reply.get(0) instanceof Long status
                && reply.get(1) instanceof Long wait) {
            if (status == 0 && wait == 0) return 0;
            if (status == 1 && wait >= 1 && wait <= maximum) return wait;
        }
        throw unavailable();
    }

    private static PasswordResetConfirmationUnavailableException unavailable() {
        return new PasswordResetConfirmationUnavailableException(null);
    }
}

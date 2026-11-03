package org.example.music.user.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.example.music.user.domain.ReauthenticationEmailBinding;
import org.example.music.user.domain.ReauthenticationEmailReservation;
import org.example.music.user.exception.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class ReauthenticationEmailRequestStore {
    private static final String PREFIX = "music:auth:reauth-email:";
    private static final RedisScript<List> RESERVE = script("reserve");
    private static final RedisScript<List> PREPARE = script("prepare");
    private static final RedisScript<List> ACTIVATE = script("activate");
    private static final RedisScript<List> CANCEL = script("cancel");
    private final StringRedisTemplate redis;

    public ReauthenticationEmailRequestStore(StringRedisTemplate redis) { this.redis = redis; }

    private static RedisScript<List> script(String name) {
        try {
            return new DefaultRedisScript<>(read("common") + "\n" + read(name), List.class);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError("Reauthentication email Redis resources are unavailable");
        }
    }

    private static String read(String name) throws IOException {
        try (var stream = new ClassPathResource("redis/reauthentication-email/" + name + ".lua").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public ReauthenticationEmailReservation reserve(ReauthenticationEmailBinding binding, String digest, String ipDigest) {
        var reply = execute(RESERVE, List.of(key("cooldown", binding), key("daily", binding), PREFIX + "ip:" + ipDigest,
                key("pending", binding), key("active", binding)), args(binding, digest));
        if (reply != null && reply.size() == 4 && reply.stream().allMatch(Long.class::isInstance)) {
            long status = (Long) reply.get(0), first = (Long) reply.get(1), end = (Long) reply.get(2), lease = (Long) reply.get(3);
            if (status == 1 && first >= 1 && first <= 86400 && end == 0 && lease == 0) {
                throw new ReauthenticationEmailRateLimitedException(first);
            }
            if (status == 0 && first >= 0 && first <= 9007199254440991L && end == first + 300000 && lease == first + 60000) {
                return new ReauthenticationEmailReservation(digest, Instant.ofEpochMilli(first), Instant.ofEpochMilli(end), Instant.ofEpochMilli(lease));
            }
        }
        throw new ReauthenticationEmailRequestUnavailableException(null);
    }

    public void prepare(ReauthenticationEmailBinding binding, ReauthenticationEmailReservation reservation, String codeHash) {
        var base = args(binding, reservation.challengeDigest());
        result(execute(PREPARE, List.of(key("pending", binding)), base[0], base[1], base[2], base[3], codeHash), true);
    }

    public void activate(ReauthenticationEmailBinding binding, ReauthenticationEmailReservation reservation) {
        result(execute(ACTIVATE, List.of(key("pending", binding), key("active", binding)), args(binding, reservation.challengeDigest())), true);
    }

    public void cancel(ReauthenticationEmailBinding binding, String digest) {
        result(execute(CANCEL, List.of(key("pending", binding)), args(binding, digest)), false);
    }

    private String key(String kind, ReauthenticationEmailBinding binding) { return PREFIX + kind + ":" + binding.userId(); }

    private String[] args(ReauthenticationEmailBinding binding, String digest) {
        return new String[]{binding.userId().toString(), binding.email(), Long.toString(binding.tokenVersion()), digest};
    }

    private List<?> execute(RedisScript<List> script, List<String> keys, Object... args) {
        try { return redis.execute(script, keys, args); }
        catch (DataAccessException failure) { throw new ReauthenticationEmailRequestUnavailableException(failure); }
    }

    private void result(List<?> result, boolean conflictAllowed) {
        if (result != null && result.size() == 1 && result.get(0) instanceof Long value) {
            if (value == 0) return;
            if (value == 1 && conflictAllowed) throw new ReauthenticationEmailRequestConflictException();
        }
        throw new ReauthenticationEmailRequestUnavailableException(null);
    }
}

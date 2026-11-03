package org.example.music.user.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.example.music.user.domain.ReauthenticationChallengeSnapshot;
import org.example.music.user.domain.ReauthenticationEmailBinding;
import org.example.music.user.exception.ReauthenticationFailedException;
import org.example.music.user.exception.ReauthenticationRateLimitedException;
import org.example.music.user.exception.ReauthenticationUnavailableException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Repository;

@Repository
public class ReauthenticationStore {
    private static final RedisScript<List> ADMIT = script("admit"), READ = script("read"), FAIL = script("fail"), CONSUME = script("consume");
    private final StringRedisTemplate redis;

    public ReauthenticationStore(StringRedisTemplate redis) { this.redis = redis; }

    private static RedisScript<List> script(String name) {
        try {
            return new DefaultRedisScript<>(resource("redis/reauthentication-email/common.lua") + "\n"
                    + resource("redis/reauthentication/" + name + ".lua"), List.class);
        } catch (IOException failure) { throw new ExceptionInInitializerError("Reauthentication Redis resources are unavailable"); }
    }

    private static String resource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    }

    public void admit(Integer userId) {
        var result = execute(ADMIT, "music:auth:reauth:user:" + userId, userId.toString());
        if (result != null && result.size() == 2 && result.get(0) instanceof Long status && result.get(1) instanceof Long seconds) {
            if (status == 0 && seconds == 0) return;
            if (status == 1 && seconds >= 1 && seconds <= 900) throw new ReauthenticationRateLimitedException(seconds);
        }
        throw new ReauthenticationUnavailableException(null);
    }

    public ReauthenticationChallengeSnapshot read(ReauthenticationEmailBinding binding, String digest) {
        var result = execute(READ, activeKey(binding), binding.userId().toString(), binding.email(), Long.toString(binding.tokenVersion()), digest);
        if (result != null && result.size() == 1 && Long.valueOf(1).equals(result.get(0))) throw new ReauthenticationFailedException();
        if (result == null || result.size() != 10 || !Long.valueOf(0).equals(result.get(0))
                || !result.subList(1,10).stream().allMatch(String.class::isInstance)) throw new ReauthenticationUnavailableException(null);
        try {
            if (!"ACTIVE".equals(result.get(1)) || !digest.equals(result.get(2)) || !binding.userId().toString().equals(result.get(3))
                    || !binding.email().equals(result.get(4)) || !Long.toString(binding.tokenVersion()).equals(result.get(5))) throw new IllegalArgumentException();
            long reserved = millis((String) result.get(6)), expires = millis((String) result.get(7));
            String hash = (String) result.get(8), attempts = (String) result.get(9);
            if (expires - reserved != 300000 || !hash.matches("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}")
                    || !attempts.matches("[0-4]")) throw new IllegalArgumentException();
            return new ReauthenticationChallengeSnapshot(binding, digest, hash, Instant.ofEpochMilli(reserved), Instant.ofEpochMilli(expires), Integer.parseInt(attempts));
        } catch (IllegalArgumentException failure) { throw new ReauthenticationUnavailableException(null); }
    }

    private static long millis(String value) {
        if (!value.matches("0|[1-9][0-9]{0,15}")) throw new IllegalArgumentException();
        long parsed = Long.parseLong(value);
        if (parsed > 9007199254740991L) throw new IllegalArgumentException();
        return parsed;
    }

    public void recordFailure(ReauthenticationChallengeSnapshot snapshot) {
        var result = execute(FAIL, activeKey(snapshot.binding()), args(snapshot));
        if (result != null && result.size() == 2 && result.get(0) instanceof Long status && result.get(1) instanceof Long count
                && ((status == 0 && count >= 1 && count <= 5) || (status == 1 && count == 0))) return;
        throw new ReauthenticationUnavailableException(null);
    }

    public void consume(ReauthenticationChallengeSnapshot snapshot) {
        var result = execute(CONSUME, activeKey(snapshot.binding()), args(snapshot));
        if (result != null && result.size() == 1 && result.get(0) instanceof Long status) {
            if (status == 0) return;
            if (status == 1) throw new ReauthenticationFailedException();
        }
        throw new ReauthenticationUnavailableException(null);
    }

    private static String activeKey(ReauthenticationEmailBinding binding) { return "music:auth:reauth-email:active:" + binding.userId(); }

    private static Object[] args(ReauthenticationChallengeSnapshot snapshot) {
        var binding = snapshot.binding();
        return new Object[]{binding.userId().toString(), binding.email(), Long.toString(binding.tokenVersion()), snapshot.challengeDigest(),
                snapshot.codeHash(), Long.toString(snapshot.reservedAt().toEpochMilli()), Long.toString(snapshot.expiresAt().toEpochMilli())};
    }

    private List<?> execute(RedisScript<List> script, String key, Object... args) {
        try { return redis.execute(script, List.of(key), args); }
        catch (DataAccessException | SerializationException | ClassCastException failure) { throw new ReauthenticationUnavailableException(failure); }
    }
}

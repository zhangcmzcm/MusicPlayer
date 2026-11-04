package org.example.music.user.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.example.music.user.domain.EmailChangeChallengeSnapshot;
import org.example.music.user.domain.EmailChangeBinding;
import org.example.music.user.domain.EmailChangeConfirmationRules;
import org.example.music.user.exception.EmailChangeVerificationFailedException;
import org.example.music.user.exception.EmailChangeConfirmationRateLimitedException;
import org.example.music.user.exception.EmailChangeConfirmationUnavailableException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Repository;

@Repository
public class EmailChangeConfirmationStore {
    private static final RedisScript<List> ADMIT = script("admit"), READ = script("read"), FAIL = script("fail"), CONSUME = script("consume");
    private final StringRedisTemplate redis;

    private final EmailChangeConfirmationRules rules;

    public EmailChangeConfirmationStore(StringRedisTemplate redis, EmailChangeConfirmationRules rules) { this.redis = redis; this.rules = rules; }

    private static RedisScript<List> script(String name) {
        try {
            return new DefaultRedisScript<>(resource("redis/email-change/common.lua") + "\n" + resource("redis/email-change-confirmation/common.lua") + "\n"
                    + resource("redis/email-change-confirmation/" + name + ".lua"), List.class);
        } catch (IOException failure) { throw new ExceptionInInitializerError("Email confirmation Redis resources are unavailable"); }
    }

    private static String resource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    }

    public void admit(Integer userId) {
        var result = execute(ADMIT, "music:auth:email-change-confirmation:user:" + userId, userId.toString());
        if (result != null && result.size() == 2 && result.get(0) instanceof Long status && result.get(1) instanceof Long seconds) {
            if (status == 0 && seconds == 0) return;
            if (status == 1 && seconds >= 1 && seconds <= 900) throw new EmailChangeConfirmationRateLimitedException(seconds);
        }
        throw new EmailChangeConfirmationUnavailableException(null);
    }

    public EmailChangeChallengeSnapshot read(Integer userId, String digest) {
        var result = execute(READ, "music:auth:email-change:active:" + userId, userId.toString(), digest);
        if (result != null && result.size() == 1 && Long.valueOf(1).equals(result.get(0))) throw new EmailChangeVerificationFailedException();
        if (result == null || result.size() != 11 || !Long.valueOf(0).equals(result.get(0))
                || !result.subList(1,11).stream().allMatch(String.class::isInstance)) throw new EmailChangeConfirmationUnavailableException(null);
        try {
            if (!"ACTIVE".equals(result.get(1)) || !digest.equals(result.get(2)) || !userId.toString().equals(result.get(3))) throw new IllegalArgumentException();
            String version = (String) result.get(6);
            if (!version.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            String current = (String) result.get(4);
            var binding = new EmailChangeBinding(userId, current.isEmpty() ? null : current, (String) result.get(5), Long.parseLong(version));
            rules.requireCanonicalBinding(binding);
            long reserved = millis((String) result.get(7)), expires = millis((String) result.get(8));
            String hash = (String) result.get(9), attempts = (String) result.get(10);
            if (expires - reserved != 300000 || !hash.matches("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}")
                    || !attempts.matches("[0-4]")) throw new IllegalArgumentException();
            return new EmailChangeChallengeSnapshot(binding, digest, hash, Instant.ofEpochMilli(reserved), Instant.ofEpochMilli(expires), Integer.parseInt(attempts));
        } catch (IllegalArgumentException failure) { throw new EmailChangeConfirmationUnavailableException(failure); }
    }

    private static long millis(String value) {
        if (!value.matches("0|[1-9][0-9]{0,15}")) throw new IllegalArgumentException();
        long parsed = Long.parseLong(value);
        if (parsed > 9007199254740991L) throw new IllegalArgumentException();
        return parsed;
    }

    public void recordFailure(EmailChangeChallengeSnapshot snapshot) {
        var result = execute(FAIL, activeKey(snapshot.binding()), args(snapshot));
        if (result != null && result.size() == 2 && result.get(0) instanceof Long status && result.get(1) instanceof Long count
                && ((status == 0 && count >= 1 && count <= 5) || (status == 1 && count == 0))) return;
        throw new EmailChangeConfirmationUnavailableException(null);
    }

    public void consume(EmailChangeChallengeSnapshot snapshot) {
        var result = execute(CONSUME, activeKey(snapshot.binding()), args(snapshot));
        if (result != null && result.size() == 1 && result.get(0) instanceof Long status) {
            if (status == 0) return;
            if (status == 1) throw new EmailChangeVerificationFailedException();
        }
        throw new EmailChangeConfirmationUnavailableException(null);
    }

    private static String activeKey(EmailChangeBinding binding) { return "music:auth:email-change:active:" + binding.userId(); }

    private static Object[] args(EmailChangeChallengeSnapshot snapshot) {
        var binding = snapshot.binding();
        return new Object[]{binding.userId().toString(), binding.currentEmail() == null ? "" : binding.currentEmail(), binding.newEmail(), Long.toString(binding.tokenVersion()), snapshot.challengeDigest(),
                snapshot.codeHash(), Long.toString(snapshot.reservedAt().toEpochMilli()), Long.toString(snapshot.expiresAt().toEpochMilli())};
    }

    private List<?> execute(RedisScript<List> script, String key, Object... args) {
        try { return redis.execute(script, List.of(key), args); }
        catch (DataAccessException | SerializationException | ClassCastException failure) { throw new EmailChangeConfirmationUnavailableException(failure); }
    }
}

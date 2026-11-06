package org.example.music.user.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class PasswordResetConfirmationStore {
    public static final String PREFIX = "music:auth:password-reset-confirmation:";
    private static final String SOURCE = PasswordResetRequestStore.PREFIX;
    private static final RedisScript<List> IP = script("admit-ip"), ACCOUNT = script("admit-account"),
            READ = script("read"), FAIL = script("fail"), CONSUME = script("consume");
    private final StringRedisTemplate redis;
    private final PasswordResetConfirmationRules rules;

    public PasswordResetConfirmationStore(StringRedisTemplate redis, PasswordResetConfirmationRules rules) {
        this.redis = redis; this.rules = rules;
    }
    private static RedisScript<List> script(String name) {
        try { return new DefaultRedisScript<>(resource("common") + "\n" + resource(name), List.class); }
        catch (IOException failure) { throw new ExceptionInInitializerError("Password reset confirmation scripts unavailable"); }
    }
    private static String resource(String name) throws IOException {
        try (var input = new ClassPathResource("redis/password-reset-confirmation/" + name + ".lua").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private List<?> execute(RedisScript<List> script, List<String> keys, Object... args) {
        try { return redis.execute(script, keys, args); }
        catch (RuntimeException failure) { throw new PasswordResetConfirmationUnavailableException(failure); }
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
        var reply = execute(script, List.of(key));
        if (reply != null && reply.size() == 2 && reply.get(0) instanceof Long status && reply.get(1) instanceof Long wait) {
            if (status == 0 && wait == 0) return 0;
            if (status == 1 && wait >= 1 && wait <= maximum) return wait;
        }
        throw unavailable();
    }
    public Optional<PasswordResetChallengeSnapshot> read(String digest) {
        if (!PasswordResetIdentifiers.isCanonicalDigest(digest)) throw unavailable();
        var reply = execute(READ, List.of(SOURCE + "challenge:" + digest));
        if (reply != null && reply.size() == 1 && Long.valueOf(1).equals(reply.getFirst())) return Optional.empty();
        if (reply == null || reply.size() != 13 || !Long.valueOf(0).equals(reply.getFirst())
                || !reply.subList(1,13).stream().allMatch(String.class::isInstance)) throw unavailable();
        try {
            if (!"1".equals(reply.get(1)) || !digest.equals(reply.get(2))) throw unavailable();
            var admission = new PasswordResetAdmission(Instant.ofEpochMilli(number((String)reply.get(5))), Instant.ofEpochMilli(number((String)reply.get(6))));
            int userId = Math.toIntExact(number((String)reply.get(7)));
            var account = new PasswordResetAccountSnapshot(userId, (String)reply.get(8), UserRole.valueOf((String)reply.get(9)), number((String)reply.get(10)));
            var snapshot = new PasswordResetChallengeSnapshot(digest, (String)reply.get(3), (String)reply.get(4), admission, account, (String)reply.get(11), Math.toIntExact(number((String)reply.get(12))));
            rules.validateChallenge(snapshot);
            return Optional.of(snapshot);
        } catch (RuntimeException failure) { throw new PasswordResetConfirmationUnavailableException(failure); }
    }
    public void recordFailure(PasswordResetChallengeSnapshot snapshot) { mutate(FAIL, snapshot); }
    public boolean consume(PasswordResetChallengeSnapshot snapshot) { return mutate(CONSUME, snapshot); }
    private boolean mutate(RedisScript<List> script, PasswordResetChallengeSnapshot snapshot) {
        rules.validateChallenge(snapshot);
        var account = snapshot.account();
        var reply = execute(script, List.of(SOURCE + "challenge:" + snapshot.digest(), SOURCE + "active:" + snapshot.emailDigest()),
                snapshot.digest(), snapshot.emailDigest(), snapshot.email(), Long.toString(snapshot.admission().admittedAt().toEpochMilli()),
                Long.toString(snapshot.admission().expiresAt().toEpochMilli()), account.userId().toString(), account.email(),
                account.userRole().name(), Long.toString(account.tokenVersion()), snapshot.codeHash());
        if (reply != null && reply.size() == 1) {
            if (Long.valueOf(0).equals(reply.getFirst())) return true;
            if (Long.valueOf(1).equals(reply.getFirst())) return false;
        }
        throw unavailable();
    }
    private static long number(String text) {
        if (!text.matches("0|[1-9][0-9]*")) throw unavailable();
        return Long.parseLong(text);
    }
    private static PasswordResetConfirmationUnavailableException unavailable() { return new PasswordResetConfirmationUnavailableException(null); }
}

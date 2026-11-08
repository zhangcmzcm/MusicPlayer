package org.example.music.user.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.music.user.domain.*;
import org.example.music.user.exception.PasswordResetVerificationUnavailableException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class PasswordResetTicketStore {
    public static final String PREFIX = "music:auth:password-reset-ticket:";
    private static final RedisScript<List> READ = script("read-current");
    private final StringRedisTemplate redis;
    private final PasswordResetTicketRules rules;
    private final PasswordResetConfirmationRules challenges;

    public PasswordResetTicketStore(StringRedisTemplate redis, PasswordResetTicketRules rules, PasswordResetConfirmationRules challenges) {
        this.redis = redis;
        this.rules = rules;
        this.challenges = challenges;
    }
    private static RedisScript<List> script(String name) {
        try {
            return new DefaultRedisScript<>(resource("redis/password-reset-confirmation/common.lua") + "\n"
                    + resource("redis/password-reset-verification/common.lua") + "\n"
                    + resource("redis/password-reset-verification/" + name + ".lua"), List.class);
        } catch (IOException failure) { throw new ExceptionInInitializerError("Password reset ticket scripts unavailable"); }
    }
    private static String resource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) { return new String(input.readAllBytes(),StandardCharsets.UTF_8); }
    }
    private List<?> execute(RedisScript<List> script, List<String> keys, Object... args) {
        try { return redis.execute(script,keys,args); }
        catch (RuntimeException failure) { throw new PasswordResetVerificationUnavailableException(failure); }
    }
    public Optional<PasswordResetTicketSnapshot> readCurrent(Integer userId) {
        if (userId == null || userId < 1) throw unavailable();
        var reply = execute(READ,List.of(PREFIX + "active:" + userId),userId.toString());
        if (reply != null && reply.size() == 1 && Long.valueOf(1).equals(reply.getFirst())) return Optional.empty();
        if (reply == null || reply.size() != 11 || !Long.valueOf(0).equals(reply.getFirst())
                || !reply.subList(1,11).stream().allMatch(String.class::isInstance)) throw unavailable();
        try {
            if (!"1".equals(reply.get(1))) throw unavailable();
            var account = new PasswordResetAccountSnapshot(Math.toIntExact(number((String)reply.get(3))),
                    (String)reply.get(6),UserRole.valueOf((String)reply.get(7)),number((String)reply.get(8)));
            if (!userId.equals(account.userId())) throw unavailable();
            var period = new PasswordResetTicketPeriod(Instant.ofEpochMilli(number((String)reply.get(9))),Instant.ofEpochMilli(number((String)reply.get(10))));
            var snapshot = new PasswordResetTicketSnapshot((String)reply.get(2),(String)reply.get(5),(String)reply.get(4),account,period);
            rules.validate(snapshot);
            return Optional.of(snapshot);
        } catch (RuntimeException failure) { throw new PasswordResetVerificationUnavailableException(failure); }
    }
    private static long number(String value) {
        if (!value.matches("0|[1-9][0-9]*")) throw unavailable();
        return Long.parseLong(value);
    }
    private static PasswordResetVerificationUnavailableException unavailable() { return new PasswordResetVerificationUnavailableException(null); }
}

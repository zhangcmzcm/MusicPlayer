package org.example.music.email.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class EmailCodeSendRateLimiter {

    private static final long EMAIL_COOLDOWN_SECONDS = 60;
    private static final long EMAIL_DAILY_LIMIT = 10;
    private static final long IP_RATE_LIMIT = 5;
    private static final DateTimeFormatter MINUTE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter DAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>(
            """
            if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) == false then
              return 1
            end
            local dailyCount = redis.call('INCR', KEYS[2])
            if dailyCount == 1 then
              redis.call('EXPIRE', KEYS[2], ARGV[2])
            end
            if dailyCount > tonumber(ARGV[3]) then
              return 2
            end
            local ipCount = redis.call('INCR', KEYS[3])
            if ipCount == 1 then
              redis.call('EXPIRE', KEYS[3], ARGV[1])
            end
            if ipCount > tonumber(ARGV[4]) then
              return 3
            end
            return 0
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public EmailCodeSendRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public EmailSendRateLimitDecision consume(
            String email,
            VerificationCodePurpose purpose,
            String clientIp
    ) {
        Instant now = Instant.now();
        LocalDateTime utcNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        String cooldownKey = "music:email:send-code:cooldown:" + purpose.name() + ":" + email;
        String dailyKey = "music:email:send-code:daily:" + purpose.name() + ":" + email + ":"
                + utcNow.toLocalDate().format(DAY_FORMAT);
        String ipKey = "music:email:send-code:ip:" + clientIp + ":" + utcNow.format(MINUTE_FORMAT);
        long dailyTtl = Math.max(1, Duration.between(
                now,
                LocalDate.of(utcNow.getYear(), utcNow.getMonth(), utcNow.getDayOfMonth())
                        .plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
        ).toSeconds());

        Long result = redisTemplate.execute(
                ACQUIRE,
                List.of(cooldownKey, dailyKey, ipKey),
                String.valueOf(EMAIL_COOLDOWN_SECONDS),
                String.valueOf(dailyTtl),
                String.valueOf(EMAIL_DAILY_LIMIT),
                String.valueOf(IP_RATE_LIMIT)
        );

        if (result == null || result == 0L) {
            return EmailSendRateLimitDecision.allowedDecision();
        }
        if (result == 1L) {
            return EmailSendRateLimitDecision.limitedDecision(
                    EmailSendRateLimitScope.EMAIL_COOLDOWN,
                    retryAfter(cooldownKey)
            );
        }
        if (result == 2L) {
            return EmailSendRateLimitDecision.limitedDecision(
                    EmailSendRateLimitScope.EMAIL_DAILY_LIMIT,
                    retryAfter(dailyKey)
            );
        }
        return EmailSendRateLimitDecision.limitedDecision(
                EmailSendRateLimitScope.IP_RATE_LIMIT,
                retryAfter(ipKey)
        );
    }

    private long retryAfter(String key) {
        Long seconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return seconds == null || seconds < 1 ? 1 : seconds;
    }
}
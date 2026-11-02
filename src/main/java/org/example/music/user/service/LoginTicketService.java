package org.example.music.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.time.Clock;
import java.time.Instant;
import java.time.DateTimeException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.example.music.user.domain.LoginPrincipal;
import org.example.music.user.domain.LoginTicketContext;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.exception.LoginTicketInvalidException;
import org.example.music.user.exception.AuthStateStoreUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
public class LoginTicketService {

    private static final String KEY_PREFIX = "music:auth:login-ticket:";
    private static final int TICKET_BYTES = 32;
    private static final long TICKET_TTL_SECONDS = 300;
    private static final Set<String> TICKET_FIELDS = Set.of("userId", "maximumRole", "loginType", "tokenVersion", "authTime");
    private static final RedisScript<Long> ISSUE = new DefaultRedisScript<>(
            """
            if #ARGV ~= 6 or tonumber(ARGV[6]) ~= 300 then
              return redis.error_reply('Invalid ticket parameters')
            end
            if redis.call('EXISTS', KEYS[1]) ~= 0 then
              return 0
            end
            redis.call('HSET', KEYS[1], 'userId', ARGV[1], 'maximumRole', ARGV[2],
              'loginType', ARGV[3], 'tokenVersion', ARGV[4], 'authTime', ARGV[5])
            return redis.call('EXPIRE', KEYS[1], ARGV[6])
            """, Long.class);
    private static final RedisScript<List> CONSUME = new DefaultRedisScript<>(
            """
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl <= 0 or redis.call('TYPE', KEYS[1]).ok ~= 'hash' then
              redis.call('DEL', KEYS[1])
              return {}
            end
            local values = redis.call('HGETALL', KEYS[1])
            redis.call('DEL', KEYS[1])
            return values
            """,
            List.class
    );

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;

    public LoginTicketService(StringRedisTemplate redisTemplate, @Qualifier("authenticationClock") Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    public String issue(LoginPrincipal principal, LoginType loginType) {
        if (principal.id() == null || principal.id() <= 0 || principal.tokenVersion() < 0
                || principal.maximumRole() == null || loginType == null || loginType == LoginType.ROLE_SELECTION
                || principal.authenticatedAt().isBefore(Instant.EPOCH) || principal.authenticatedAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Invalid verified login context");
        }
        byte[] ticketBytes = new byte[TICKET_BYTES];
        secureRandom.nextBytes(ticketBytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ticketBytes);
        String key = key(ticket);

        try {
            Long published = redisTemplate.execute(ISSUE, List.of(key), String.valueOf(principal.id()),
                    principal.maximumRole().name(), loginType.name(), String.valueOf(principal.tokenVersion()),
                    String.valueOf(principal.authenticatedAt().getEpochSecond()),
                    String.valueOf(TICKET_TTL_SECONDS));
            if (!Long.valueOf(1L).equals(published)) {
                throw new AuthStateStoreUnavailableException("Login ticket publication was not confirmed",
                        new IllegalStateException("Ticket publication result is unavailable"));
            }
            return ticket;
        } catch (DataAccessException exception) {
            throw new AuthStateStoreUnavailableException("Authentication state store is unavailable", exception);
        }
    }

    public LoginTicketContext consume(String ticket) {
        try {
            List<?> values = redisTemplate.execute(CONSUME, List.of(key(ticket)));
            if (values == null) {
                throw new AuthStateStoreUnavailableException("Login ticket consumption was not confirmed",
                        new IllegalStateException("Ticket consumption result is unavailable"));
            }
            if (values.size() != TICKET_FIELDS.size() * 2) {
                throw new LoginTicketInvalidException();
            }
            Map<String, String> fields = new HashMap<>();
            for (int index = 0; index < values.size(); index += 2) {
                if (!(values.get(index) instanceof String field) || !TICKET_FIELDS.contains(field)
                        || !(values.get(index + 1) instanceof String value)
                        || fields.putIfAbsent(field, value) != null) {
                    throw new LoginTicketInvalidException();
                }
            }
            if (!fields.keySet().equals(TICKET_FIELDS)
                    || !fields.get("userId").matches("[1-9][0-9]*")
                    || !fields.get("tokenVersion").matches("0|[1-9][0-9]*")
                    || !fields.get("authTime").matches("0|[1-9][0-9]*")) {
                throw new LoginTicketInvalidException();
            }
            LoginType loginType = LoginType.valueOf(fields.get("loginType"));
            if (loginType == LoginType.ROLE_SELECTION) {
                throw new LoginTicketInvalidException();
            }
            Instant authenticatedAt = Instant.ofEpochSecond(Long.parseLong(fields.get("authTime")));
            if (authenticatedAt.isAfter(clock.instant())) {
                throw new LoginTicketInvalidException();
            }
            return new LoginTicketContext(
                    Integer.parseInt(fields.get("userId")),
                    UserRole.valueOf(fields.get("maximumRole")),
                    loginType,
                    Long.parseLong(fields.get("tokenVersion")),
                    authenticatedAt
            );
        } catch (DataAccessException exception) {
            throw new AuthStateStoreUnavailableException("Authentication state store is unavailable", exception);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new LoginTicketInvalidException();
        }
    }

    private String key(String ticket) {
        return KEY_PREFIX + digest(ticket);
    }

    private String digest(String ticket) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(ticket.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

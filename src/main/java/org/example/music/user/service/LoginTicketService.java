package org.example.music.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
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
    private static final RedisScript<List> CONSUME = new DefaultRedisScript<>(
            """
            local values = redis.call('HGETALL', KEYS[1])
            if #values == 0 then
              return {}
            end
            redis.call('DEL', KEYS[1])
            return values
            """,
            List.class
    );

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public LoginTicketService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String issue(LoginPrincipal principal, LoginType loginType) {
        byte[] ticketBytes = new byte[TICKET_BYTES];
        secureRandom.nextBytes(ticketBytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ticketBytes);
        String key = key(ticket);

        try {
            redisTemplate.opsForHash().put(key, "userId", String.valueOf(principal.id()));
            redisTemplate.opsForHash().put(key, "maximumRole", principal.maximumRole().name());
            redisTemplate.opsForHash().put(key, "loginType", loginType.name());
            redisTemplate.expire(key, java.time.Duration.ofSeconds(TICKET_TTL_SECONDS));
            return ticket;
        } catch (DataAccessException exception) {
            throw new AuthStateStoreUnavailableException("Authentication state store is unavailable", exception);
        }
    }

    public LoginTicketContext consume(String ticket) {
        try {
            @SuppressWarnings("unchecked")
            List<String> values = redisTemplate.execute(CONSUME, List.of(key(ticket)));
            if (values == null || values.size() < 6) {
                throw new LoginTicketInvalidException();
            }

            return new LoginTicketContext(
                    Integer.parseInt(value(values, "userId")),
                    UserRole.valueOf(value(values, "maximumRole")),
                    LoginType.valueOf(value(values, "loginType"))
            );
        } catch (DataAccessException exception) {
            throw new AuthStateStoreUnavailableException("Authentication state store is unavailable", exception);
        } catch (IllegalArgumentException exception) {
            throw new LoginTicketInvalidException();
        }
    }

    private String value(List<String> values, String field) {
        for (int index = 0; index < values.size(); index += 2) {
            if (field.equals(values.get(index))) {
                return values.get(index + 1);
            }
        }
        throw new LoginTicketInvalidException();
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
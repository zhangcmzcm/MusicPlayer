package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.example.music.user.domain.LoginPrincipal;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.exception.AuthStateStoreUnavailableException;
import org.example.music.user.exception.LoginTicketInvalidException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class LoginTicketServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "+1", "-1", "00", "01", "١", "1.0", "1e2",
            "9223372036854775808", "9223372036854775807", "999999999999", "1\n"})
    void should_reject_noncanonical_out_of_range_or_future_authentication_time(String time) {
        reply(List.of("userId", "42", "maximumRole", "ADMIN", "loginType", "EMAIL_CODE",
                "tokenVersion", "7", "authTime", time));
        assertThrows(LoginTicketInvalidException.class, () -> service.consume("ticket"));
    }

    @Test
    void should_reject_old_four_fields_and_invalid_issue_time_without_redis_write() {
        reply(List.of("userId", "42", "maximumRole", "ADMIN", "loginType", "EMAIL_CODE", "tokenVersion", "7"));
        assertThrows(LoginTicketInvalidException.class, () -> service.consume("ticket"));
        clearInvocations(redis);
        for (var time : new java.time.Instant[]{java.time.Instant.EPOCH.minusSeconds(1), java.time.Instant.now().plusSeconds(600)}) {
            assertThrows(IllegalArgumentException.class, () -> service.issue(
                    new LoginPrincipal(42, "admin", null, UserRole.ADMIN, 7, time), LoginType.EMAIL_CODE));
        }
        verifyNoInteractions(redis);
    }

    @Test
    void should_reject_duplicate_unknown_null_and_nonstring_authentication_time() {
        var base = List.<Object>of("userId", "42", "maximumRole", "ADMIN", "loginType", "EMAIL_CODE", "tokenVersion", "7");
        for (Object time : new Object[]{null, 0L}) {
            var fields = new ArrayList<>(base);
            fields.add("authTime"); fields.add(time);
            reply(fields);
            assertThrows(LoginTicketInvalidException.class, () -> service.consume("ticket"));
        }
        var duplicate = new ArrayList<>(base);
        duplicate.addAll(List.of("authTime", "0", "authTime", "0"));
        reply(duplicate);
        assertThrows(LoginTicketInvalidException.class, () -> service.consume("ticket"));
    }
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final LoginTicketService service = new LoginTicketService(redis, java.time.Clock.systemUTC());

    private static java.util.List<Object> ticketFields(Object... values) {
        var fields = new java.util.ArrayList<Object>(java.util.Arrays.asList(values));
        fields.add("authTime");
        fields.add("0");
        return fields;
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "7", "9223372036854775807"})
    void should_parse_exact_fields_in_any_order_and_preserve_version(String version) {
        reply(ticketFields("tokenVersion", version, "loginType", "EMAIL_CODE", "maximumRole", "ADMIN", "userId", "42"));
        var context = service.consume("test-ticket");
        assertEquals(42, context.userId());
        assertEquals(UserRole.ADMIN, context.maximumRole());
        assertEquals(LoginType.EMAIL_CODE, context.loginType());
        assertEquals(Long.parseLong(version), context.tokenVersion());
        assertEquals(java.time.Instant.EPOCH, context.authenticatedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "+1", "-1", "007", "00", "1.0", "1e2", "١", "9223372036854775808", "7\n"})
    void should_reject_noncanonical_or_out_of_range_version(String version) {
        reply(ticketFields("userId", "42", "maximumRole", "ADMIN", "loginType", "USERNAME_PASSWORD", "tokenVersion", version));
        assertThrows(LoginTicketInvalidException.class, () -> service.consume("test-ticket"));
    }

    static Stream<List<?>> malformed() {
        return Stream.of(ticketFields(),
                ticketFields("userId", "42", "maximumRole", "ADMIN", "loginType", "USERNAME_PASSWORD"),
                ticketFields("userId", "42", "maximumRole", "ADMIN", "loginType", "USERNAME_PASSWORD", "other", "0"),
                ticketFields("userId", "42", "maximumRole", "ADMIN", "loginType", "USERNAME_PASSWORD", "tokenVersion", "0", "other", "x"),
                ticketFields("userId", "42", "maximumRole", "ADMIN", "tokenVersion", "0", "tokenVersion", "0"),
                ticketFields("userId", 42, "maximumRole", "ADMIN", "loginType", "USERNAME_PASSWORD", "tokenVersion", "0"),
                ticketFields("userId", "0", "maximumRole", "ADMIN", "loginType", "USERNAME_PASSWORD", "tokenVersion", "0"),
                ticketFields("userId", "-1", "maximumRole", "ADMIN", "loginType", "USERNAME_PASSWORD", "tokenVersion", "0"),
                ticketFields("userId", "+42", "maximumRole", "ADMIN", "loginType", "USERNAME_PASSWORD", "tokenVersion", "0"),
                ticketFields("userId", "042", "maximumRole", "ADMIN", "loginType", "USERNAME_PASSWORD", "tokenVersion", "0"),
                ticketFields("userId", "2147483648", "maximumRole", "ADMIN", "loginType", "USERNAME_PASSWORD", "tokenVersion", "0"),
                ticketFields("userId", "42", "maximumRole", "UNKNOWN", "loginType", "USERNAME_PASSWORD", "tokenVersion", "0"),
                ticketFields("userId", "42", "maximumRole", "ADMIN", "loginType", "ROLE_SELECTION", "tokenVersion", "0"),
                ticketFields("userId", "42", "maximumRole", "ADMIN", "loginType", "UNKNOWN", "tokenVersion", "0"));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void should_reject_missing_unknown_duplicate_or_malformed_fields(List<?> values) {
        reply(values);
        assertThrows(LoginTicketInvalidException.class, () -> service.consume("test-ticket"));
    }

    @Test
    void should_reject_null_field_value() {
        var values = new ArrayList<Object>(ticketFields("userId", "42", "maximumRole", "ADMIN",
                "loginType", "EMAIL_CODE", "tokenVersion", "0"));
        values.set(7, null);
        reply(values);
        assertThrows(LoginTicketInvalidException.class, () -> service.consume("test-ticket"));
    }

    @Test
    void should_publish_version_and_ttl_in_one_script() {
        reply(1L);
        String ticket = service.issue(new LoginPrincipal(42, "admin", null, UserRole.ADMIN, 7L, java.time.Instant.EPOCH), LoginType.EMAIL_PASSWORD);
        assertTrue(ticket.matches("[A-Za-z0-9_-]{43}"));
        verify(redis).execute(any(RedisScript.class), anyList(), eq("42"), eq("ADMIN"),
                eq("EMAIL_PASSWORD"), eq("7"), eq("0"), eq("300"));
        verifyNoMoreInteractions(redis);
    }

    @Test
    void should_not_return_ticket_when_publication_result_is_uncertain() {
        reply(null);
        assertThrows(AuthStateStoreUnavailableException.class,
                () -> service.issue(new LoginPrincipal(42, "admin", null, UserRole.ADMIN, 0L, java.time.Instant.EPOCH), LoginType.EMAIL_PASSWORD));
    }

    @Test
    void should_not_treat_missing_script_result_as_valid_or_missing_ticket() {
        reply(null);
        assertThrows(AuthStateStoreUnavailableException.class, () -> service.consume("test-ticket"));
    }

    @Test
    void should_translate_redis_failure_without_returning_credentials() {
        doThrow(new DataAccessResourceFailureException("redis"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        assertThrows(AuthStateStoreUnavailableException.class, () -> service.consume("test-ticket"));
        assertThrows(AuthStateStoreUnavailableException.class,
                () -> service.issue(new LoginPrincipal(42, "admin", null, UserRole.ADMIN, 0L, java.time.Instant.EPOCH), LoginType.EMAIL_PASSWORD));
    }

    @SuppressWarnings("unchecked")
    private void reply(Object result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}

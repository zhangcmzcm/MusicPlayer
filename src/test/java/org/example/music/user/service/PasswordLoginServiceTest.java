package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.exception.InvalidCredentialsException;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PasswordLoginServiceTest {
    @Test
    void should_capture_time_immediately_after_successful_matches() {
        var clock = mock(java.time.Clock.class);
        var start = java.time.Instant.parse("2026-11-02T12:00:00Z");
        var completed = start.plusSeconds(30).plusMillis(900);
        var current = new java.util.concurrent.atomic.AtomicReference<>(start);
        when(clock.instant()).thenAnswer(invocation -> current.get());
        var user = UserEntity.create("alice", null, "hash");
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(hasher.matches("password", "hash")).thenAnswer(invocation -> {
            current.set(completed);
            return true;
        });
        var result = new PasswordLoginService(users, hasher, clock)
                .authenticate(LoginType.USERNAME_PASSWORD, "alice", "password");
        assertEquals(completed.truncatedTo(java.time.temporal.ChronoUnit.SECONDS), result.authenticatedAt());
        verify(clock, times(1)).instant();
    }
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final PasswordLoginService service = new PasswordLoginService(users, hasher, java.time.Clock.systemUTC());

    @ParameterizedTest
    @CsvSource({"USERNAME_PASSWORD,0", "USERNAME_PASSWORD,7", "EMAIL_PASSWORD,0", "EMAIL_PASSWORD,7"})
    void should_freeze_account_version_before_password_verification(LoginType type, long version) {
        UserEntity user = mock(UserEntity.class);
        AtomicLong state = new AtomicLong(version);
        when(user.getId()).thenReturn(42);
        when(user.getUsername()).thenReturn("alice");
        when(user.getEmail()).thenReturn("alice@example.com");
        when(user.getUserRole()).thenReturn(UserRole.USER);
        when(user.getTokenVersion()).thenAnswer(invocation -> state.get());
        when(user.getPasswordHash()).thenReturn("hash");
        String account = type == LoginType.USERNAME_PASSWORD ? "alice" : "alice@example.com";
        if (type == LoginType.USERNAME_PASSWORD) {
            when(users.findByUsername(account)).thenReturn(Optional.of(user));
        } else {
            when(users.findByEmail(account)).thenReturn(Optional.of(user));
        }
        when(hasher.matches("password", "hash")).thenAnswer(invocation -> {
            state.incrementAndGet();
            return true;
        });
        var principal = service.authenticate(type, account, "password");
        assertEquals(42, principal.id());
        assertEquals("alice", principal.username());
        assertEquals(UserRole.USER, principal.maximumRole());
        assertEquals(version, principal.tokenVersion());
        verify(user, times(1)).getTokenVersion();
    }

    @Test
    void should_reject_wrong_password() {
        UserEntity user = new UserEntity("alice", null, "hash", UserRole.USER);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(hasher.matches("wrong", "hash")).thenReturn(false);
        assertThrows(InvalidCredentialsException.class,
                () -> service.authenticate(LoginType.USERNAME_PASSWORD, "alice", "wrong"));
    }

    @Test
    void should_not_verify_password_for_unknown_account() {
        when(users.findByUsername("alice")).thenReturn(Optional.empty());
        assertThrows(InvalidCredentialsException.class,
                () -> service.authenticate(LoginType.USERNAME_PASSWORD, "alice", "password"));
        verifyNoInteractions(hasher);
    }
}

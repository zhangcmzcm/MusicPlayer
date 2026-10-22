package org.example.music.verification.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.example.music.security.PasswordHasher;
import org.example.music.verification.domain.EmailVerificationCodeSnapshot;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.example.music.verification.exception.EmailVerificationCodeException;
import org.example.music.verification.exception.VerificationCodeStoreUnavailableException;
import org.example.music.verification.repository.EmailVerificationCodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class EmailVerificationCodeServiceTest {

    @Mock
    private EmailVerificationCodeStore emailVerificationCodeStore;

    @Mock
    private PasswordHasher passwordHasher;

    @InjectMocks
    private EmailVerificationCodeService service;

    @Test
    void should_return_verified_code_version_when_hash_matches() {
        whenStore(snapshot("code-hash", "code-version", 0, null));
        when(passwordHasher.matches("123456", "code-hash")).thenReturn(true);

        VerifiedEmailCode verified = service.validate("alice@example.com", "123456");

        assertEquals("alice@example.com", verified.email());
        assertEquals("code-version", verified.version());
    }

    @Test
    void should_reject_missing_code_as_invalid() {
        whenStore(null);

        EmailVerificationCodeException exception = assertThrows(
                EmailVerificationCodeException.class,
                () -> service.validate("alice@example.com", "123456")
        );

        assertEquals(EmailVerificationCodeException.Failure.INVALID, exception.getFailure());
    }

    @Test
    void should_reject_used_code() {
        whenStore(snapshot("code-hash", "code-version", 0, Instant.parse("2026-10-22T10:00:00Z")));

        EmailVerificationCodeException exception = assertThrows(
                EmailVerificationCodeException.class,
                () -> service.validate("alice@example.com", "123456")
        );

        assertEquals(EmailVerificationCodeException.Failure.ALREADY_USED, exception.getFailure());
    }

    @Test
    void should_reject_code_at_attempt_limit() {
        whenStore(snapshot("code-hash", "code-version", 5, null));

        EmailVerificationCodeException exception = assertThrows(
                EmailVerificationCodeException.class,
                () -> service.validate("alice@example.com", "123456")
        );

        assertEquals(EmailVerificationCodeException.Failure.ATTEMPTS_EXCEEDED, exception.getFailure());
    }

    @Test
    void should_increment_failed_attempt_and_reject_mismatch() {
        whenStore(snapshot("code-hash", "code-version", 0, null));
        when(passwordHasher.matches("654321", "code-hash")).thenReturn(false);
        when(emailVerificationCodeStore.incrementFailedAttempts(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER),
                eq("code-version")
        )).thenReturn(1);

        EmailVerificationCodeException exception = assertThrows(
                EmailVerificationCodeException.class,
                () -> service.validate("alice@example.com", "654321")
        );

        assertEquals(EmailVerificationCodeException.Failure.INVALID, exception.getFailure());
    }

    @Test
    void should_promote_fifth_failed_attempt_to_attempt_limit() {
        whenStore(snapshot("code-hash", "code-version", 4, null));
        when(passwordHasher.matches("654321", "code-hash")).thenReturn(false);
        when(emailVerificationCodeStore.incrementFailedAttempts(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER),
                eq("code-version")
        )).thenReturn(5);

        EmailVerificationCodeException exception = assertThrows(
                EmailVerificationCodeException.class,
                () -> service.validate("alice@example.com", "654321")
        );

        assertEquals(EmailVerificationCodeException.Failure.ATTEMPTS_EXCEEDED, exception.getFailure());
    }

    @Test
    void should_translate_redis_read_failure_to_store_unavailable() {
        when(emailVerificationCodeStore.find(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER)
        )).thenThrow(new RedisConnectionFailureException("redis unavailable"));

        VerificationCodeStoreUnavailableException exception = assertThrows(
                VerificationCodeStoreUnavailableException.class,
                () -> service.validate("alice@example.com", "123456")
        );

        assertEquals("Email verification code store is unavailable", exception.getMessage());
    }

    @Test
    void should_not_fail_request_when_post_commit_consumption_fails() {
        VerifiedEmailCode verified = new VerifiedEmailCode("alice@example.com", "code-version");
        when(emailVerificationCodeStore.consume(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER),
                eq("code-version"),
                any(Instant.class)
        )).thenThrow(new RedisConnectionFailureException("redis unavailable"));

        assertDoesNotThrow(() -> service.consumeAfterCommit(verified));
    }

    @Test
    void should_atomically_consume_matching_version_after_commit() {
        VerifiedEmailCode verified = new VerifiedEmailCode("alice@example.com", "code-version");
        when(emailVerificationCodeStore.consume(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER),
                eq("code-version"),
                any(Instant.class)
        )).thenReturn(true);

        service.consumeAfterCommit(verified);

        verify(emailVerificationCodeStore).consume(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER),
                eq("code-version"),
                any(Instant.class)
        );
    }

    private void whenStore(EmailVerificationCodeSnapshot snapshot) {
        Optional<EmailVerificationCodeSnapshot> value = Optional.ofNullable(snapshot);
        when(emailVerificationCodeStore.find(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER)
        )).thenReturn(value);
    }

    private EmailVerificationCodeSnapshot snapshot(
            String codeHash,
            String version,
            int attempts,
            Instant usedAt
    ) {
        return new EmailVerificationCodeSnapshot(codeHash, version, attempts, usedAt);
    }
}
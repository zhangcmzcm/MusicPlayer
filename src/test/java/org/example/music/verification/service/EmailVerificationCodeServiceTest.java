package org.example.music.verification.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.example.music.security.PasswordHasher;
import org.example.music.verification.domain.VerificationCodeEntity;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.example.music.verification.exception.EmailVerificationCodeException;
import org.example.music.verification.repository.VerificationCodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class EmailVerificationCodeServiceTest {

    @Mock
    private VerificationCodeRepository verificationCodeRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @InjectMocks
    private EmailVerificationCodeService service;

    @Test
    void should_consume_active_matching_code() {
        VerificationCodeEntity code = activeCode();
        whenLatest(code);
        when(passwordHasher.matches("123456", code.getCodeHash())).thenReturn(true);

        assertDoesNotThrow(() -> service.consume("alice@example.com", "123456"));

        assertNotNull(code.getUsedAt());
    }

    @Test
    void should_reject_mismatched_code() {
        VerificationCodeEntity code = activeCode();
        whenLatest(code);
        when(passwordHasher.matches("654321", code.getCodeHash())).thenReturn(false);

        EmailVerificationCodeException exception = assertThrows(
                EmailVerificationCodeException.class,
                () -> service.consume("alice@example.com", "654321")
        );

        assertEquals(EmailVerificationCodeException.Failure.INVALID, exception.getFailure());
    }

    @Test
    void should_reject_expired_code_before_hash_comparison() {
        VerificationCodeEntity code = new VerificationCodeEntity(
                "alice@example.com",
                VerificationCodePurpose.REGISTER,
                "hash",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                null
        );
        whenLatest(code);

        EmailVerificationCodeException exception = assertThrows(
                EmailVerificationCodeException.class,
                () -> service.consume("alice@example.com", "123456")
        );

        assertEquals(EmailVerificationCodeException.Failure.EXPIRED, exception.getFailure());
        verifyNoInteractions(passwordHasher);
    }

    @Test
    void should_reject_already_used_code() {
        VerificationCodeEntity code = activeCode();
        code.consume(LocalDateTime.now(ZoneOffset.UTC));
        whenLatest(code);

        EmailVerificationCodeException exception = assertThrows(
                EmailVerificationCodeException.class,
                () -> service.consume("alice@example.com", "123456")
        );

        assertEquals(EmailVerificationCodeException.Failure.ALREADY_USED, exception.getFailure());
    }

    private VerificationCodeEntity activeCode() {
        return new VerificationCodeEntity(
                "alice@example.com",
                VerificationCodePurpose.REGISTER,
                "code-hash",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10),
                null
        );
    }

    private void whenLatest(VerificationCodeEntity code) {
        when(verificationCodeRepository.findLatestForUpdate(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER),
                any(Pageable.class)
        )).thenReturn(List.of(code));
    }
}
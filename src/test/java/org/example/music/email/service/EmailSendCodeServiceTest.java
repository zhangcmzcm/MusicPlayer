package org.example.music.email.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.example.music.common.util.EmailVerificationCodeSender;
import org.example.music.common.util.SecureVerificationCodeGenerator;
import org.example.music.email.dto.EmailSendCodeRequest;
import org.example.music.email.dto.EmailSendCodeResponse;
import org.example.music.email.exception.EmailSendFailedException;
import org.example.music.email.exception.SendCodeRateLimitedException;
import org.example.music.security.PasswordHasher;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.example.music.verification.exception.VerificationCodeStoreUnavailableException;
import org.example.music.verification.repository.EmailVerificationCodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class EmailSendCodeServiceTest {

    @Mock
    private EmailCodeSendRateLimiter emailCodeSendRateLimiter;

    @Mock
    private SecureVerificationCodeGenerator secureVerificationCodeGenerator;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private EmailVerificationCodeStore emailVerificationCodeStore;

    @Mock
    private EmailVerificationCodeSender emailVerificationCodeSender;

    @InjectMocks
    private EmailSendCodeService service;

    private final EmailSendCodeRequest request = new EmailSendCodeRequest(
            "Alice@Example.COM",
            VerificationCodePurpose.REGISTER
    );

    @Test
    void should_generate_hash_save_and_send_code_when_rate_limit_allows() {
        Instant expiresAt = Instant.parse("2026-10-22T12:40:00Z");
        when(emailCodeSendRateLimiter.consume(
                "alice@example.com",
                VerificationCodePurpose.REGISTER,
                "198.51.100.10"
        )).thenReturn(EmailSendRateLimitDecision.allowedDecision());
        when(secureVerificationCodeGenerator.generate()).thenReturn("123456");
        when(passwordHasher.hash("123456")).thenReturn("code-hash");
        when(emailVerificationCodeStore.save(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER),
                eq("code-hash"),
                any(String.class)
        )).thenReturn(expiresAt);

        EmailSendCodeResponse response = service.send(request, "198.51.100.10");

        assertEquals(VerificationCodePurpose.REGISTER, response.purpose());
        assertEquals(expiresAt, response.expiresAt());
        verify(emailVerificationCodeSender).send(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER),
                eq("123456"),
                eq(expiresAt)
        );
    }

    @Test
    void should_reject_when_rate_limit_is_exhausted() {
        when(emailCodeSendRateLimiter.consume(
                "alice@example.com",
                VerificationCodePurpose.REGISTER,
                "198.51.100.10"
        )).thenReturn(EmailSendRateLimitDecision.limitedDecision(
                EmailSendRateLimitScope.EMAIL_COOLDOWN,
                42
        ));

        SendCodeRateLimitedException exception = assertThrows(
                SendCodeRateLimitedException.class,
                () -> service.send(request, "198.51.100.10")
        );

        assertEquals(EmailSendRateLimitScope.EMAIL_COOLDOWN, exception.getScope());
        assertEquals(42, exception.getRetryAfterSeconds());
        verifyNoInteractions(secureVerificationCodeGenerator);
        verifyNoInteractions(emailVerificationCodeStore);
        verifyNoInteractions(emailVerificationCodeSender);
    }

    @Test
    void should_remove_same_code_version_when_email_delivery_fails() {
        Instant expiresAt = Instant.parse("2026-10-22T12:40:00Z");
        whenReadyToGenerate();
        ArgumentCaptor<String> versionCaptor = ArgumentCaptor.forClass(String.class);
        when(emailVerificationCodeStore.save(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER),
                eq("code-hash"),
                versionCaptor.capture()
        )).thenReturn(expiresAt);
        doThrow(new EmailSendFailedException("failed", new RuntimeException("smtp")))
                .when(emailVerificationCodeSender)
                .send(eq("alice@example.com"), eq(VerificationCodePurpose.REGISTER), eq("123456"), eq(expiresAt));

        assertThrows(EmailSendFailedException.class, () -> service.send(request, "198.51.100.10"));

        verify(emailVerificationCodeStore).removeIfVersion(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER),
                eq(versionCaptor.getValue())
        );
    }

    @Test
    void should_not_send_email_when_code_store_write_fails() {
        whenReadyToGenerate();
        when(emailVerificationCodeStore.save(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER),
                eq("code-hash"),
                any(String.class)
        )).thenThrow(new RedisConnectionFailureException("redis unavailable"));

        VerificationCodeStoreUnavailableException exception = assertThrows(
                VerificationCodeStoreUnavailableException.class,
                () -> service.send(request, "198.51.100.10")
        );

        assertEquals("Email verification code store is unavailable", exception.getMessage());
        verifyNoInteractions(emailVerificationCodeSender);
    }

    @Test
    void should_translate_rate_limiter_redis_failure_to_store_unavailable() {
        when(emailCodeSendRateLimiter.consume(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER),
                eq("198.51.100.10")
        )).thenThrow(new RedisConnectionFailureException("redis unavailable"));

        assertThrows(
                VerificationCodeStoreUnavailableException.class,
                () -> service.send(request, "198.51.100.10")
        );

        verifyNoInteractions(emailVerificationCodeSender);
    }

    private void whenReadyToGenerate() {
        when(emailCodeSendRateLimiter.consume(
                "alice@example.com",
                VerificationCodePurpose.REGISTER,
                "198.51.100.10"
        )).thenReturn(EmailSendRateLimitDecision.allowedDecision());
        when(secureVerificationCodeGenerator.generate()).thenReturn("123456");
        when(passwordHasher.hash("123456")).thenReturn("code-hash");
    }
}
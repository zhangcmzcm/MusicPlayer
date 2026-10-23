package org.example.music.email.service;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class EmailSendCodeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailSendCodeService.class);

    private final EmailCodeSendRateLimiter emailCodeSendRateLimiter;
    private final SecureVerificationCodeGenerator secureVerificationCodeGenerator;
    private final PasswordHasher passwordHasher;
    private final EmailVerificationCodeStore emailVerificationCodeStore;
    private final EmailVerificationCodeSender emailVerificationCodeSender;

    public EmailSendCodeService(
            EmailCodeSendRateLimiter emailCodeSendRateLimiter,
            SecureVerificationCodeGenerator secureVerificationCodeGenerator,
            PasswordHasher passwordHasher,
            EmailVerificationCodeStore emailVerificationCodeStore,
            EmailVerificationCodeSender emailVerificationCodeSender
    ) {
        this.emailCodeSendRateLimiter = emailCodeSendRateLimiter;
        this.secureVerificationCodeGenerator = secureVerificationCodeGenerator;
        this.passwordHasher = passwordHasher;
        this.emailVerificationCodeStore = emailVerificationCodeStore;
        this.emailVerificationCodeSender = emailVerificationCodeSender;
    }

    public EmailSendCodeResponse send(EmailSendCodeRequest request, String clientIp) {
        String email = normalizeEmail(request.email());

        EmailSendRateLimitDecision rateLimitDecision = consumeRateLimit(
                email,
                request.purpose(),
                clientIp
        );
        if (!rateLimitDecision.allowed()) {
            throw new SendCodeRateLimitedException(
                    rateLimitDecision.scope(),
                    rateLimitDecision.retryAfterSeconds(),
                    "Email verification code requests are temporarily rate limited"
            );
        }

        String code = secureVerificationCodeGenerator.generate();
        String version = UUID.randomUUID().toString();
        Instant expiresAt = saveCode(email, request.purpose(), code, version);

        try {
            emailVerificationCodeSender.send(email, request.purpose(), code, expiresAt);
        } catch (EmailSendFailedException exception) {
            removeFailedCode(email, request.purpose(), version);
            throw exception;
        }

        return new EmailSendCodeResponse(request.purpose(), expiresAt);
    }

    private EmailSendRateLimitDecision consumeRateLimit(
            String email,
            VerificationCodePurpose purpose,
            String clientIp
    ) {
        try {
            return emailCodeSendRateLimiter.consume(email, purpose, clientIp);
        } catch (DataAccessException exception) {
            throw new VerificationCodeStoreUnavailableException(
                    "Email verification code store is unavailable",
                    exception
            );
        }
    }

    private Instant saveCode(
            String email,
            VerificationCodePurpose purpose,
            String code,
            String version
    ) {
        try {
            return emailVerificationCodeStore.save(
                    email,
                    purpose,
                    passwordHasher.hash(code),
                    version
            );
        } catch (DataAccessException exception) {
            throw new VerificationCodeStoreUnavailableException(
                    "Email verification code store is unavailable",
                    exception
            );
        }
    }

    private void removeFailedCode(
            String email,
            VerificationCodePurpose purpose,
            String version
    ) {
        try {
            emailVerificationCodeStore.removeIfVersion(email, purpose, version);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Failed to remove undelivered email verification code, traceId={}, exceptionType={}",
                    MDC.get("traceId"),
                    exception.getClass().getSimpleName(),
                    exception
            );
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
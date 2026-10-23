package org.example.music.verification.service;

import java.time.Instant;
import java.util.Optional;
import org.example.music.security.PasswordHasher;
import org.example.music.verification.domain.EmailVerificationCodeSnapshot;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.example.music.verification.exception.EmailVerificationCodeException;
import org.example.music.verification.exception.VerificationCodeStoreUnavailableException;
import org.example.music.verification.repository.EmailVerificationCodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationCodeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailVerificationCodeService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final EmailVerificationCodeStore emailVerificationCodeStore;
    private final PasswordHasher passwordHasher;

    public EmailVerificationCodeService(
            EmailVerificationCodeStore emailVerificationCodeStore,
            PasswordHasher passwordHasher
    ) {
        this.emailVerificationCodeStore = emailVerificationCodeStore;
        this.passwordHasher = passwordHasher;
    }

    public VerifiedEmailCode validate(String email, String code) {
        return validate(email, code, VerificationCodePurpose.REGISTER);
    }

    public VerifiedEmailCode validate(
            String email,
            String code,
            VerificationCodePurpose purpose
    ) {
        EmailVerificationCodeSnapshot snapshot = find(email, purpose);
        if (snapshot.usedAt() != null) {
            throw new EmailVerificationCodeException(
                    EmailVerificationCodeException.Failure.ALREADY_USED,
                    "Email verification code has already been used"
            );
        }
        if (snapshot.attempts() >= MAX_ATTEMPTS) {
            throw attemptsExceededException();
        }

        if (!passwordHasher.matches(code, snapshot.codeHash())) {
            int attempts = incrementFailedAttempts(email, purpose, snapshot.version());
            if (attempts >= MAX_ATTEMPTS) {
                throw attemptsExceededException();
            }
            throw new EmailVerificationCodeException(
                    EmailVerificationCodeException.Failure.INVALID,
                    "Email verification code is invalid"
            );
        }

        return new VerifiedEmailCode(email, snapshot.version(), purpose);
    }

    public boolean consume(VerifiedEmailCode verifiedEmailCode) {
        try {
            return emailVerificationCodeStore.consume(
                    verifiedEmailCode.email(),
                    verifiedEmailCode.purpose(),
                    verifiedEmailCode.version(),
                    Instant.now()
            );
        } catch (DataAccessException exception) {
            throw new VerificationCodeStoreUnavailableException(
                    "Email verification code store is unavailable",
                    exception
            );
        }
    }

    public void consumeAfterCommit(VerifiedEmailCode verifiedEmailCode) {
        try {
            boolean consumed = consume(verifiedEmailCode);
            if (!consumed) {
                LOGGER.warn(
                        "Email verification code was not consumed after registration commit, traceId={}, reason={}",
                        MDC.get("traceId"),
                        "missing, used, or replaced"
                );
            }
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Email verification code consumption failed after registration commit, traceId={}, exceptionType={}",
                    MDC.get("traceId"),
                    exception.getClass().getSimpleName(),
                    exception
            );
        }
    }

    private EmailVerificationCodeSnapshot find(
            String email,
            VerificationCodePurpose purpose
    ) {
        try {
            Optional<EmailVerificationCodeSnapshot> snapshot = emailVerificationCodeStore.find(
                    email,
                    purpose
            );
            return snapshot.orElseThrow(() -> new EmailVerificationCodeException(
                    EmailVerificationCodeException.Failure.INVALID,
                    "Email verification code is invalid"
            ));
        } catch (DataAccessException exception) {
            throw new VerificationCodeStoreUnavailableException(
                    "Email verification code store is unavailable",
                    exception
            );
        }
    }

    private int incrementFailedAttempts(
            String email,
            VerificationCodePurpose purpose,
            String version
    ) {
        try {
            int attempts = emailVerificationCodeStore.incrementFailedAttempts(
                    email,
                    purpose,
                    version
            );
            if (attempts < 0) {
                throw new EmailVerificationCodeException(
                        EmailVerificationCodeException.Failure.INVALID,
                        "Email verification code is invalid"
                );
            }
            return attempts;
        } catch (DataAccessException exception) {
            throw new VerificationCodeStoreUnavailableException(
                    "Email verification code store is unavailable",
                    exception
            );
        }
    }

    private EmailVerificationCodeException attemptsExceededException() {
        return new EmailVerificationCodeException(
                EmailVerificationCodeException.Failure.ATTEMPTS_EXCEEDED,
                "Email verification code attempts are exhausted"
        );
    }
}
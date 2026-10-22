package org.example.music.verification.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.example.music.security.PasswordHasher;
import org.example.music.verification.domain.VerificationCodeEntity;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.example.music.verification.exception.EmailVerificationCodeException;
import org.example.music.verification.repository.VerificationCodeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationCodeService {

    private static final int MAX_ATTEMPTS = 5;

    private final VerificationCodeRepository verificationCodeRepository;
    private final PasswordHasher passwordHasher;

    public EmailVerificationCodeService(
            VerificationCodeRepository verificationCodeRepository,
            PasswordHasher passwordHasher
    ) {
        this.verificationCodeRepository = verificationCodeRepository;
        this.passwordHasher = passwordHasher;
    }

    @Transactional
    public void consume(String email, String code) {
        VerificationCodeEntity verificationCode = verificationCodeRepository
                .findLatestForUpdate(email, VerificationCodePurpose.REGISTER, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElseThrow(() -> new EmailVerificationCodeException(
                        EmailVerificationCodeException.Failure.INVALID,
                        null,
                        "Email verification code is invalid"
                ));

        if (verificationCode.isUsed()) {
            throw new EmailVerificationCodeException(
                    EmailVerificationCodeException.Failure.ALREADY_USED,
                    verificationCode.getId(),
                    "Email verification code has already been used"
            );
        }

        if (verificationCode.hasExceededAttempts(MAX_ATTEMPTS)) {
            throw new EmailVerificationCodeException(
                    EmailVerificationCodeException.Failure.ATTEMPTS_EXCEEDED,
                    verificationCode.getId(),
                    "Email verification code attempts are exhausted"
            );
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (verificationCode.isExpired(now)) {
            throw new EmailVerificationCodeException(
                    EmailVerificationCodeException.Failure.EXPIRED,
                    verificationCode.getId(),
                    "Email verification code has expired"
            );
        }

        if (!passwordHasher.matches(code, verificationCode.getCodeHash())) {
            throw new EmailVerificationCodeException(
                    EmailVerificationCodeException.Failure.INVALID,
                    verificationCode.getId(),
                    "Email verification code is invalid"
            );
        }

        verificationCode.consume(now);
    }
}

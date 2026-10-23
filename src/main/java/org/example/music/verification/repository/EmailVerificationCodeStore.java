package org.example.music.verification.repository;

import java.time.Instant;
import java.util.Optional;
import org.example.music.verification.domain.EmailVerificationCodeSnapshot;
import org.example.music.verification.domain.VerificationCodePurpose;

public interface EmailVerificationCodeStore {

    Optional<EmailVerificationCodeSnapshot> find(String email, VerificationCodePurpose purpose);

    Instant save(
            String email,
            VerificationCodePurpose purpose,
            String codeHash,
            String version
    );

    int incrementFailedAttempts(String email, VerificationCodePurpose purpose, String version);

    boolean consume(String email, VerificationCodePurpose purpose, String version, Instant usedAt);

    boolean removeIfVersion(String email, VerificationCodePurpose purpose, String version);
}
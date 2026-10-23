package org.example.music.common.util;

import java.time.Instant;
import org.example.music.verification.domain.VerificationCodePurpose;

public interface EmailVerificationCodeSender {

    void send(
            String email,
            VerificationCodePurpose purpose,
            String code,
            Instant expiresAt
    );
}
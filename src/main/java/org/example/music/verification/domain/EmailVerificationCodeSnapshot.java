package org.example.music.verification.domain;

import java.time.Instant;

public record EmailVerificationCodeSnapshot(
        String codeHash,
        String version,
        int attempts,
        Instant usedAt
) {
}
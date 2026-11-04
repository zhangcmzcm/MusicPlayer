package org.example.music.user.domain;

import java.time.Instant;

public record EmailChangeChallengeSnapshot(EmailChangeBinding binding, String challengeDigest, String codeHash,
        Instant reservedAt, Instant expiresAt, int attempts) {
    @Override public String toString() { return "EmailChangeChallengeSnapshot[redacted]"; }
}

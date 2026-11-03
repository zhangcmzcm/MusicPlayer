package org.example.music.user.domain;

import java.time.Instant;

public record ReauthenticationChallengeSnapshot(ReauthenticationEmailBinding binding, String challengeDigest,
        String codeHash, Instant reservedAt, Instant expiresAt, int attempts) {
    @Override public String toString() { return "ReauthenticationChallengeSnapshot[credentials=<redacted>, expiresAt=" + expiresAt + "]"; }
}

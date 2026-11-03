package org.example.music.user.domain;

import java.time.Instant;

public record ReauthenticationEmailReservation(String challengeDigest, Instant reservedAt, Instant expiresAt, Instant leaseUntil) {
    @Override public String toString() {
        return "ReauthenticationEmailReservation[challengeDigest=<redacted>, reservedAt=" + reservedAt + "]";
    }
}

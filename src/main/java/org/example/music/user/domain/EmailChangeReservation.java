package org.example.music.user.domain;

import java.time.Instant;

public record EmailChangeReservation(String challengeDigest, Instant reservedAt, Instant expiresAt, Instant leaseUntil) {
    @Override public String toString() {
        return "EmailChangeReservation[challengeDigest=<redacted>, reservedAt=" + reservedAt + "]";
    }
}

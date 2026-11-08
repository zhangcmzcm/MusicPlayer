package org.example.music.user.domain;

import java.time.Instant;

public record PasswordResetTicketPeriod(Instant issuedAt, Instant expiresAt) {
    @Override public String toString() { return "PasswordResetTicketPeriod[redacted]"; }
}

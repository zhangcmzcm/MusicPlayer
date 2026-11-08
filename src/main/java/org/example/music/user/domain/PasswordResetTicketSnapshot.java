package org.example.music.user.domain;

public record PasswordResetTicketSnapshot(String digest, String emailDigest, String email,
        PasswordResetAccountSnapshot account, PasswordResetTicketPeriod period) {
    @Override public String toString() { return "PasswordResetTicketSnapshot[redacted]"; }
}

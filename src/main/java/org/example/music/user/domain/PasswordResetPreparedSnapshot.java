package org.example.music.user.domain;

public record PasswordResetPreparedSnapshot(Integer userId, String rawEmail, UserRole role,
        long tokenVersion, String passwordHash) {
    @Override public String toString() { return "PasswordResetPreparedSnapshot[redacted]"; }
}

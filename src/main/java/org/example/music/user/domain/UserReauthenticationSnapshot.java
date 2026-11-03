package org.example.music.user.domain;

public record UserReauthenticationSnapshot(Integer userId, String email, String passwordHash, UserRole userRole, long tokenVersion) {
    @Override public String toString() { return "UserReauthenticationSnapshot[userId=" + userId + ", credentials=<redacted>]"; }
}

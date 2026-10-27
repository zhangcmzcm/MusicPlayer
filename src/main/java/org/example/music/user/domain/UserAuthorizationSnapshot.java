package org.example.music.user.domain;

public record UserAuthorizationSnapshot(
        Integer userId,
        String username,
        String email,
        UserRole userRole,
        long tokenVersion
) {
}

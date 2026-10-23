package org.example.music.user.domain;

public record LoginPrincipal(
        Integer id,
        String username,
        String email,
        UserRole maximumRole
) {
}
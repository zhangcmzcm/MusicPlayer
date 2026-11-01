package org.example.music.user.domain;

public record UserProfileSnapshot(
        Integer id, String username, String email, UserRole userRole, String createdAt
) {
}

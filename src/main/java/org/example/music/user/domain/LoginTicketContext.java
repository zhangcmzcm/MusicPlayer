package org.example.music.user.domain;

import org.example.music.user.dto.LoginType;

public record LoginTicketContext(
        Integer userId,
        UserRole maximumRole,
        LoginType loginType
) {
}
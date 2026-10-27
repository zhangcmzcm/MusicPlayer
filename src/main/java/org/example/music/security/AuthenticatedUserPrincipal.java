package org.example.music.security;

import java.security.Principal;
import org.example.music.user.domain.UserRole;

public record AuthenticatedUserPrincipal(
        Integer userId,
        UserRole loginRole
) implements Principal {

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}

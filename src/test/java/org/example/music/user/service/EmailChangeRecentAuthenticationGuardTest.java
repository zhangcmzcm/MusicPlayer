package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import org.example.music.security.*;
import org.example.music.user.domain.UserRole;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.Test;

class EmailChangeRecentAuthenticationGuardTest {
    private final Instant now = Instant.parse("2026-11-04T00:00:00Z");
    private final EmailChangeRecentAuthenticationGuard guard = new EmailChangeRecentAuthenticationGuard(Clock.fixed(now, ZoneOffset.UTC));
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(1, UserRole.USER);

    @Test void should_allow_299_and_exactly_300_seconds() {
        for (int age : new int[]{299, 300}) assertDoesNotThrow(() -> guard.requireRecent(actor, new AuthenticatedSessionContext(0, now.minusSeconds(age))));
    }
    @Test void should_reject_missing_or_expired_authentication_without_rounding() {
        assertThrows(ReauthenticationRequiredException.class, () -> guard.requireRecent(actor, new AuthenticatedSessionContext(0, null)));
        assertThrows(ReauthenticationRequiredException.class, () -> guard.requireRecent(actor, new AuthenticatedSessionContext(0, now.minusSeconds(300).minusNanos(1))));
    }
    @Test void should_reject_invalid_context_or_future_time() {
        assertThrows(InvalidTokenException.class, () -> guard.requireRecent(null, new AuthenticatedSessionContext(0, now)));
        assertThrows(InvalidTokenException.class, () -> guard.requireRecent(actor, null));
        assertThrows(InvalidTokenException.class, () -> guard.requireRecent(actor, new AuthenticatedSessionContext(-1, now)));
        assertThrows(InvalidTokenException.class, () -> guard.requireRecent(actor, new AuthenticatedSessionContext(0, now.plusNanos(1))));
    }
}

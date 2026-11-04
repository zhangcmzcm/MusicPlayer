package org.example.music.user.service;

import java.time.Clock;
import java.time.Duration;
import org.example.music.security.*;
import org.example.music.user.exception.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class EmailChangeRecentAuthenticationGuard {
    private final Clock clock;
    public EmailChangeRecentAuthenticationGuard(@Qualifier("authenticationClock") Clock clock) { this.clock = clock; }

    public void requireRecent(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session) {
        if (actor == null || actor.userId() == null || actor.userId() <= 0 || actor.loginRole() == null
                || session == null || session.tokenVersion() < 0) throw new InvalidTokenException();
        if (session.authenticatedAt() == null) throw new ReauthenticationRequiredException();
        var age = Duration.between(session.authenticatedAt(), clock.instant());
        if (age.isNegative()) throw new InvalidTokenException();
        if (age.compareTo(Duration.ofSeconds(300)) > 0) throw new ReauthenticationRequiredException();
    }
}

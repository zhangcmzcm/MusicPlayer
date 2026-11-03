package org.example.music.user.service;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;
import org.example.music.security.AuthenticatedSessionContext;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.security.JwtTokenService;
import org.example.music.security.JwtTokenSubject;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.ReauthenticationEmailBinding;
import org.example.music.user.domain.ReauthenticationMethod;
import org.example.music.user.domain.UserReauthenticationSnapshot;
import org.example.music.user.dto.ReauthenticationRequest;
import org.example.music.user.dto.ReauthenticationResponse;
import org.example.music.user.exception.CurrentEmailNotBoundException;
import org.example.music.user.exception.CurrentUserRevokedException;
import org.example.music.user.exception.InvalidTokenException;
import org.example.music.user.exception.ReauthenticationDiagnostics;
import org.example.music.user.exception.ReauthenticationFailedException;
import org.example.music.user.exception.ReauthenticationRateLimitedException;
import org.example.music.user.exception.ReauthenticationUnavailableException;
import org.example.music.user.repository.ReauthenticationStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ReauthenticationService {
    private final ReauthenticationSnapshotReader reader;
    private final ReauthenticationStore store;
    private final PasswordHasher hasher;
    private final JwtTokenService tokens;
    private final Clock clock;

    public ReauthenticationService(ReauthenticationSnapshotReader reader, ReauthenticationStore store, PasswordHasher hasher,
            JwtTokenService tokens, @Qualifier("authenticationClock") Clock clock) {
        this.reader = reader;
        this.store = store;
        this.hasher = hasher;
        this.tokens = tokens;
        this.clock = clock;
    }

    public ReauthenticationResponse reauthenticate(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext context,
            ReauthenticationRequest request) {
        if (actor == null || actor.userId() == null || actor.userId() <= 0 || actor.loginRole() == null || context == null || context.tokenVersion() < 0) {
            throw new InvalidTokenException();
        }
        try {
            store.admit(actor.userId());
            var initial = load(actor, context);
            final Instant verifiedAt;
            final UserReauthenticationSnapshot current;
            if (request.method() == ReauthenticationMethod.PASSWORD) {
                if (!hasher.matches(request.password(), initial.passwordHash())) throw new ReauthenticationFailedException();
                verifiedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
                current = load(actor, context);
                if (!initial.passwordHash().equals(current.passwordHash())) throw new ReauthenticationFailedException();
            } else {
                String email = initialEmail(initial.email());
                var binding = new ReauthenticationEmailBinding(actor.userId(), email, context.tokenVersion());
                var challenge = store.read(binding, digest(request.challengeId()));
                if (!hasher.matches(request.code(), challenge.codeHash())) {
                    store.recordFailure(challenge);
                    throw new ReauthenticationFailedException();
                }
                verifiedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
                // 候选时间来自匹配完成，但消费及最后DB复核均确认后才能授予资格。
                store.consume(challenge);
                current = load(actor, context);
                if (current.email() == null || current.email().isBlank() || !email.equals(normalize(current.email()))) {
                    throw new ReauthenticationFailedException();
                }
            }
            var subject = new JwtTokenSubject(actor.userId(), current.userRole(), context.tokenVersion());
            var token = tokens.issueReauthenticated(subject, actor.loginRole(), request.method(), verifiedAt);
            return new ReauthenticationResponse(token.tokenValue(), "Bearer", token.expiresAt(), verifiedAt);
        } catch (InvalidTokenException | CurrentUserRevokedException | CurrentEmailNotBoundException
                | ReauthenticationFailedException | ReauthenticationRateLimitedException | ReauthenticationUnavailableException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Reauthentication failed unexpectedly", ReauthenticationDiagnostics.sanitized(failure));
        }
    }

    private UserReauthenticationSnapshot load(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext context) {
        var snapshot = reader.load(actor.userId());
        if (!actor.userId().equals(snapshot.userId()) || snapshot.userRole() == null || snapshot.tokenVersion() < 0) throw new ReauthenticationUnavailableException(null);
        if (snapshot.tokenVersion() != context.tokenVersion() || !actor.loginRole().isAllowedBy(snapshot.userRole())) throw new CurrentUserRevokedException();
        return snapshot;
    }

    private static String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }

    private static String initialEmail(String value) {
        if (value == null || value.isBlank()) throw new CurrentEmailNotBoundException();
        String email = normalize(value);
        try {
            if (email.indexOf('\r') >= 0 || email.indexOf('\n') >= 0) throw new AddressException();
            new InternetAddress(email, true).validate();
            return email;
        } catch (AddressException failure) { throw new ReauthenticationUnavailableException(failure); }
    }

    private static String digest(String value) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required digest algorithm unavailable"); }
    }
}

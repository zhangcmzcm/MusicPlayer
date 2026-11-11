package org.example.music.user.domain;

import org.example.music.user.exception.PasswordResetConfirmationUnavailableException;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetConfirmationRules {
    private final PasswordResetEmailRules emails;
    public PasswordResetConfirmationRules(PasswordResetEmailRules emails) { this.emails = emails; }

    public boolean isSupportedPasswordHash(String hash) {
        if (hash == null || !hash.matches("\\$2[aby]\\$[0-9]{2}\\$[A-Za-z0-9./]{53}")) return false;
        int cost = Integer.parseInt(hash.substring(4,6));
        return cost >= 4 && cost <= 31;
    }

    public void requireStoredPasswordHash(String hash) {
        if (hash == null || hash.isBlank()) throw unavailable();
    }

    public void requirePasswordHash(String hash) {
        if (!isSupportedPasswordHash(hash)) throw unavailable();
    }

    public void validateChallenge(PasswordResetChallengeSnapshot value) {
        try {
            if (value == null || !PasswordResetIdentifiers.isCanonicalDigest(value.digest())
                    || !PasswordResetIdentifiers.isCanonicalDigest(value.emailDigest())
                    || !PasswordResetIdentifiers.digest(value.email()).equals(value.emailDigest())
                    || !emails.normalize(value.email()).equals(value.email())
                    || value.admission() == null || value.account() == null
                    || value.attempts() < 0 || value.attempts() > 4) throw unavailable();
            long first = value.admission().admittedAt().toEpochMilli();
            long last = value.admission().expiresAt().toEpochMilli();
            if (first < 0 || first > 9007199254440991L || last != first + 300000) throw unavailable();
            var account = value.account();
            if (account.userId() == null || account.userId() < 1 || account.userRole() == null
                    || account.tokenVersion() < 0 || !emails.matches(value.email(), account.email())) throw unavailable();
            requirePasswordHash(value.codeHash());
        } catch (RuntimeException failure) { throw new PasswordResetConfirmationUnavailableException(failure); }
    }

    public boolean matches(PasswordResetChallengeSnapshot expected, PasswordResetAccountSnapshot actual) {
        if (actual == null) return false;
        if (actual.userId() == null || actual.userId() < 1 || actual.userRole() == null || actual.tokenVersion() < 0) throw unavailable();
        return expected.account().equals(actual);
    }

    private static PasswordResetConfirmationUnavailableException unavailable() {
        return new PasswordResetConfirmationUnavailableException(null);
    }
}

package org.example.music.user.domain;

import org.example.music.user.exception.PasswordResetVerificationUnavailableException;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetTicketRules {
    private final PasswordResetEmailRules emails;
    public PasswordResetTicketRules(PasswordResetEmailRules emails) { this.emails = emails; }

    public void validate(PasswordResetTicketSnapshot value) {
        try {
            if (value == null || !PasswordResetIdentifiers.isCanonicalDigest(value.digest())
                    || !PasswordResetIdentifiers.isCanonicalDigest(value.emailDigest())
                    || !PasswordResetIdentifiers.digest(value.email()).equals(value.emailDigest())
                    || !emails.normalize(value.email()).equals(value.email())
                    || value.account() == null || value.period() == null) throw unavailable();
            var account = value.account();
            if (account.userId() == null || account.userId() < 1 || account.userRole() == null
                    || account.tokenVersion() < 0 || !emails.matches(value.email(),account.email())) throw unavailable();
            var period = value.period();
            long first = period.issuedAt().toEpochMilli();
            long last = period.expiresAt().toEpochMilli();
            if (first < 0 || first > 9007199254440991L || last != first + 300000
                    || period.issuedAt().getNano() % 1000000 != 0 || period.expiresAt().getNano() % 1000000 != 0) throw unavailable();
        } catch (RuntimeException failure) { throw new PasswordResetVerificationUnavailableException(failure); }
    }
    private static PasswordResetVerificationUnavailableException unavailable() { return new PasswordResetVerificationUnavailableException(null); }
}

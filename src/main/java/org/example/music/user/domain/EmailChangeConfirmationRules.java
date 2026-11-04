package org.example.music.user.domain;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.util.Locale;
import java.util.Objects;
import org.example.music.user.exception.*;
import org.springframework.stereotype.Component;

@Component
public class EmailChangeConfirmationRules {
    private final Validator validator;
    public EmailChangeConfirmationRules(Validator validator) { this.validator = validator; }
    private record Address(@NotBlank @Email @Size(max=120) String value) {
        @Override public String toString() { return "Address[redacted]"; }
    }

    public String normalizeStoredEmail(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            if (raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0
                    || raw.codePoints().anyMatch(point -> point >= 0xd800 && point <= 0xdfff)) throw new AddressException();
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            if (!validator.validate(new Address(normalized)).isEmpty()) throw new AddressException();
            var address = new InternetAddress(normalized, true);
            address.validate();
            if (address.getPersonal() != null || address.isGroup() || !normalized.equals(address.getAddress())) throw new AddressException();
            return normalized;
        } catch (AddressException failure) { throw new EmailChangeConfirmationUnavailableException(failure); }
    }

    public void requireCanonicalBinding(EmailChangeBinding binding) {
        String target = normalizeStoredEmail(binding.newEmail());
        if (target == null || !target.equals(binding.newEmail())
                || !Objects.equals(normalizeStoredEmail(binding.currentEmail()), binding.currentEmail())) {
            throw new EmailChangeConfirmationUnavailableException(null);
        }
    }

    public void requireBinding(EmailChangeChallengeSnapshot challenge, EmailChangeAccountSnapshot account, long tokenVersion) {
        if (!challenge.binding().userId().equals(account.userId())) throw new EmailChangeConfirmationUnavailableException(null);
        if (account.tokenVersion() != tokenVersion) throw new CurrentUserRevokedException();
        if (challenge.binding().tokenVersion() != tokenVersion) throw new EmailChangeVerificationFailedException();
        if (!Objects.equals(challenge.binding().currentEmail(), normalizeStoredEmail(account.email()))) throw new EmailChangeConflictException();
    }
}

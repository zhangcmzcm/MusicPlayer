package org.example.music.user.service;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Locale;
import org.example.music.security.*;
import org.example.music.user.domain.EmailChangeBinding;
import org.example.music.user.exception.*;
import org.example.music.user.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class EmailChangeSnapshotReader {
    private final UserRepository users;
    private final TransactionTemplate transaction;

    public EmailChangeSnapshotReader(UserRepository users, PlatformTransactionManager manager) {
        this.users = users;
        transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
    }

    public EmailChangeBinding loadBinding(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session, String normalizedNewEmail) {
        try {
            return transaction.execute(status -> {
                var row = users.findEmailChangeSnapshot(actor.userId()).orElseThrow(CurrentUserRevokedException::new);
                if (!actor.userId().equals(row.userId()) || row.userRole() == null || row.tokenVersion() < 0) {
                    throw new EmailChangeRequestUnavailableException(null);
                }
                if (session.tokenVersion() != row.tokenVersion() || !actor.loginRole().isAllowedBy(row.userRole())) {
                    throw new CurrentUserRevokedException();
                }
                return new EmailChangeBinding(row.userId(), normalizeStoredEmail(row.email()), normalizedNewEmail, row.tokenVersion());
            });
        } catch (DataAccessException | TransactionException failure) { throw new EmailChangeRequestUnavailableException(failure); }
    }

    public void requireTargetAvailable(EmailChangeBinding binding) {
        try {
            transaction.executeWithoutResult(status -> {
                if (binding.newEmail().equals(binding.currentEmail())) throw new EmailUnchangedException();
                var owner = users.findEmailOwnerId(binding.newEmail());
                if (owner.isPresent()) {
                    if (binding.userId().equals(owner.get())) throw new EmailUnchangedException();
                    throw new EmailChangeEmailAlreadyExistsException();
                }
            });
        } catch (DataAccessException | TransactionException failure) { throw new EmailChangeRequestUnavailableException(failure); }
    }

    private static String normalizeStoredEmail(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            if (raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0) throw new AddressException();
            String email = raw.trim().toLowerCase(Locale.ROOT);
            var address = new InternetAddress(email, true);
            address.validate();
            if (email.length() > 120 || address.getPersonal() != null || address.isGroup() || !email.equals(address.getAddress())) {
                throw new AddressException();
            }
            return email;
        } catch (AddressException failure) { throw new EmailChangeRequestUnavailableException(failure); }
    }
}

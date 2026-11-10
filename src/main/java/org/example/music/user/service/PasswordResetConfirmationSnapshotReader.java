package org.example.music.user.service;

import java.util.Optional;
import org.example.music.user.domain.*;
import org.example.music.user.exception.PasswordResetConfirmationUnavailableException;
import org.example.music.user.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PasswordResetConfirmationSnapshotReader {
    private final UserRepository users;
    private final PasswordResetConfirmationRules rules;
    private final TransactionTemplate transaction;
    public PasswordResetConfirmationSnapshotReader(UserRepository users, PlatformTransactionManager manager, PasswordResetConfirmationRules rules) {
        this.users = users; this.rules = rules;
        this.transaction = new TransactionTemplate(manager); this.transaction.setReadOnly(true);
    }
    public boolean isCurrent(PasswordResetChallengeSnapshot challenge) {
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> users.findPasswordResetSnapshotById(challenge.account().userId())
                    .filter(account -> rules.matches(challenge,account)).isPresent()));
        } catch (DataAccessException | TransactionException failure) { throw new PasswordResetConfirmationUnavailableException(failure); }
    }
    public Optional<PasswordResetPreparedSnapshot> prepareTicket(PasswordResetTicketSnapshot ticket) {
        try {
            return transaction.execute(status -> users.findPasswordResetPreparedSnapshot(ticket.account().userId()).filter(prepared -> {
                var actual = new PasswordResetAccountSnapshot(prepared.userId(), prepared.rawEmail(), prepared.role(), prepared.tokenVersion());
                if (actual.userId() == null || actual.userId() < 1 || actual.userRole() == null || actual.tokenVersion() < 0) {
                    throw new PasswordResetConfirmationUnavailableException(null);
                }
                if (!ticket.account().equals(actual)) return false;
                rules.requirePasswordHash(prepared.passwordHash());
                return true;
            }));
        } catch (DataAccessException | TransactionException failure) {
            throw new PasswordResetConfirmationUnavailableException(failure);
        }
    }
}

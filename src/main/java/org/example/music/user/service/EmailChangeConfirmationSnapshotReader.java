package org.example.music.user.service;

import org.example.music.security.*;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class EmailChangeConfirmationSnapshotReader {
    private final UserRepository users;
    private final TransactionTemplate transaction;
    private final EmailChangeConfirmationRules rules;

    public EmailChangeConfirmationSnapshotReader(UserRepository users, PlatformTransactionManager manager, EmailChangeConfirmationRules rules) {
        this.users = users; this.rules = rules;
        transaction = new TransactionTemplate(manager); transaction.setReadOnly(true);
    }

    public EmailChangeAccountSnapshot load(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session) {
        try { return transaction.execute(status -> read(actor, session)); }
        catch (DataAccessException | TransactionException failure) { throw new EmailChangeConfirmationUnavailableException(failure); }
    }

    public EmailChangeAccountSnapshot prepare(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session, EmailChangeChallengeSnapshot challenge) {
        try {
            return transaction.execute(status -> {
                var row = read(actor, session);
                rules.requireBinding(challenge, row, session.tokenVersion());
                if (row.tokenVersion() == Long.MAX_VALUE) throw new EmailChangeConfirmationUnavailableException(null);
                var owner = users.findEmailOwnerId(challenge.binding().newEmail());
                if (owner.isPresent()) {
                    if (row.userId().equals(owner.get())) throw new EmailChangeConflictException();
                    throw new EmailChangeEmailAlreadyExistsException();
                }
                return row;
            });
        } catch (DataAccessException | TransactionException failure) { throw new EmailChangeConfirmationUnavailableException(failure); }
    }

    private EmailChangeAccountSnapshot read(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session) {
        var row = users.findEmailChangeSnapshot(actor.userId()).orElseThrow(CurrentUserRevokedException::new);
        if (!actor.userId().equals(row.userId()) || row.userRole() == null || row.tokenVersion() < 0) throw new EmailChangeConfirmationUnavailableException(null);
        if (row.tokenVersion() != session.tokenVersion() || !actor.loginRole().isAllowedBy(row.userRole())) throw new CurrentUserRevokedException();
        rules.normalizeStoredEmail(row.email());
        return row;
    }
}

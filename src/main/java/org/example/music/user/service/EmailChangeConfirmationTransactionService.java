package org.example.music.user.service;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.example.music.security.*;
import org.example.music.user.domain.EmailChangeAccountSnapshot;
import org.example.music.user.exception.*;
import org.example.music.user.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailChangeConfirmationTransactionService {
    private final UserRepository users;
    private final TransactionTemplate transaction;
    public EmailChangeConfirmationTransactionService(UserRepository users, PlatformTransactionManager manager) {
        this.users = users; transaction = new TransactionTemplate(manager);
    }

    public void update(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session, EmailChangeAccountSnapshot expected, String newEmail) {
        if (!actor.userId().equals(expected.userId()) || session.tokenVersion() != expected.tokenVersion()
                || !actor.loginRole().isAllowedBy(expected.userRole())) throw new CurrentUserRevokedException();
        if (expected.tokenVersion() == Long.MAX_VALUE) throw new EmailChangeConfirmationUnavailableException(null);
        try {
            transaction.executeWithoutResult(status -> {
                int changed = users.changeEmailIfSnapshotMatches(actor.userId(), expected.email(), expected.tokenVersion(), expected.userRole().name(), newEmail);
                if (changed == 0) {
                    var current = users.findEmailChangeSnapshot(actor.userId()).orElseThrow(CurrentUserRevokedException::new);
                    if (current.tokenVersion() != session.tokenVersion() || current.userRole() == null
                            || !actor.loginRole().isAllowedBy(current.userRole())) throw new CurrentUserRevokedException();
                    throw new EmailChangeConflictException();
                }
                if (changed != 1) throw new IllegalStateException("Unexpected email update count");
            });
        } catch (DataAccessException | TransactionException failure) {
            if (isEmailUniqueConflict(failure)) throw new EmailChangeEmailAlreadyExistsException();
            throw new EmailChangeConfirmationUnavailableException(failure);
        }
    }

    private static boolean isEmailUniqueConflict(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.size() < 32 && seen.add(current); current = current.getCause()) {
            if (current instanceof SQLException sql && sql.getErrorCode() == 1062 && "23000".equals(sql.getSQLState())) {
                // 只匹配驱动消息最后的约束名，不能让重复值中伪造的索引文字改变分类。
                String message = sql.getMessage();
                if (message != null && message.matches("(?s).*for key ['`](?:[^'`]+\\.)?ix_users_email['`]$")) return true;
            }
        }
        return false;
    }
}

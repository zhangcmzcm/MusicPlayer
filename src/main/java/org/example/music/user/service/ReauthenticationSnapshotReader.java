package org.example.music.user.service;

import org.example.music.user.domain.UserReauthenticationSnapshot;
import org.example.music.user.exception.CurrentUserRevokedException;
import org.example.music.user.exception.ReauthenticationUnavailableException;
import org.example.music.user.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ReauthenticationSnapshotReader {
    private final UserRepository users;
    private final TransactionTemplate transaction;

    public ReauthenticationSnapshotReader(UserRepository users, PlatformTransactionManager manager) {
        this.users = users;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setReadOnly(true);
    }

    public UserReauthenticationSnapshot load(Integer userId) {
        try {
            return transaction.execute(status -> {
                var row = users.findReauthenticationSnapshot(userId).orElseThrow(CurrentUserRevokedException::new);
                if (!userId.equals(row.userId()) || row.userId() <= 0 || row.userRole() == null || row.tokenVersion() < 0
                        || row.passwordHash() == null || row.passwordHash().isBlank()) throw new ReauthenticationUnavailableException(null);
                return row;
            });
        } catch (DataAccessException | TransactionException failure) { throw new ReauthenticationUnavailableException(failure); }
    }
}

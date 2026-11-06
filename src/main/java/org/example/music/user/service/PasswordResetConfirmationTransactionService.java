package org.example.music.user.service;

import org.example.music.user.domain.PasswordResetPreparedSnapshot;
import org.example.music.user.exception.*;
import org.example.music.user.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordResetConfirmationTransactionService {
    private final UserRepository users;
    private final TransactionTemplate transaction;
    public PasswordResetConfirmationTransactionService(UserRepository users, PlatformTransactionManager manager) {
        this.users = users; this.transaction = new TransactionTemplate(manager);
    }
    public void update(PasswordResetPreparedSnapshot expected, String newHash) {
        if (expected.tokenVersion() == Long.MAX_VALUE) throw new PasswordResetConfirmationUnavailableException(null);
        try {
            transaction.executeWithoutResult(status -> {
                int changed = users.resetPasswordIfSnapshotMatches(expected.userId(),expected.rawEmail(),expected.role().name(),
                        expected.passwordHash(),expected.tokenVersion(),newHash);
                if (changed == 0) throw new PasswordResetVerificationFailedException();
                if (changed != 1) throw new IllegalStateException("Unexpected password reset update count");
            });
        } catch (DataAccessException | TransactionException failure) { throw new PasswordResetConfirmationUnavailableException(failure); }
    }
}

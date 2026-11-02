package org.example.music.user.service;

import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.security.PasswordHasher;
import org.example.music.user.dto.ChangePasswordRequest;
import org.example.music.user.exception.CurrentUserRevokedException;
import org.example.music.user.exception.NewPasswordSameAsOldException;
import org.example.music.user.exception.OldPasswordIncorrectException;
import org.example.music.user.exception.PasswordChangeConflictException;
import org.example.music.user.exception.PasswordChangeStoreUnavailableException;
import org.example.music.user.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CurrentUserPasswordService {
    private final UserRepository users;
    private final PasswordHasher hasher;
    private final PasswordChangeRateLimiter limiter;
    private final TransactionTemplate transaction;

    public CurrentUserPasswordService(UserRepository users, PasswordHasher hasher,
            PasswordChangeRateLimiter limiter, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.hasher = hasher;
        this.limiter = limiter;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void changePassword(AuthenticatedUserPrincipal actor, ChangePasswordRequest request) {
        limiter.consumeOrThrow(actor.userId());
        try {
            var snapshot = users.findPasswordSnapshot(actor.userId()).orElseThrow(CurrentUserRevokedException::new);
            if (!hasher.matches(request.oldPassword(), snapshot.passwordHash())) {
                throw new OldPasswordIncorrectException();
            }
            if (hasher.matches(request.newPassword(), snapshot.passwordHash())) {
                throw new NewPasswordSameAsOldException();
            }
            if (snapshot.tokenVersion() == Long.MAX_VALUE) {
                throw new PasswordChangeStoreUnavailableException(null);
            }
            String hash = hasher.hash(request.newPassword());
            // BCrypt 与 Redis 不持有数据库写锁；CAS 拒绝校验之后发生的密码或版本变化。
            transaction.executeWithoutResult(status -> {
                int changed = users.changePasswordIfSnapshotMatches(actor.userId(), snapshot.passwordHash(),
                        snapshot.tokenVersion(), hash);
                if (changed == 0) {
                    if (!users.existsById(actor.userId())) {
                        throw new CurrentUserRevokedException();
                    }
                    throw new PasswordChangeConflictException();
                }
                if (changed != 1) {
                    throw new IllegalStateException("Unexpected password update count");
                }
            });
        } catch (DataAccessException | TransactionException exception) {
            throw new PasswordChangeStoreUnavailableException(exception);
        }
    }
}

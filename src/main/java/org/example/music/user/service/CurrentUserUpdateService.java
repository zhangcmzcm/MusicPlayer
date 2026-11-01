package org.example.music.user.service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.dto.CurrentUserResponse;
import org.example.music.user.dto.UpdateCurrentUserRequest;
import org.example.music.user.exception.CurrentUserRevokedException;
import org.example.music.user.exception.UserAlreadyExistsException;
import org.example.music.user.exception.UserProfileUpdateUnavailableException;
import org.example.music.user.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CurrentUserUpdateService {
    private final UserRepository users;
    private final TransactionTemplate transaction;

    public CurrentUserUpdateService(UserRepository users, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public CurrentUserResponse updateCurrentUser(AuthenticatedUserPrincipal actor, UpdateCurrentUserRequest request) {
        try {
            return transaction.execute(status -> {
                try {
                    users.updateUsernameIfChanged(actor.userId(), request.username());
                } catch (DataAccessException exception) {
                    // 仅此 UPDATE 改变 username 唯一列；投影与提交故障不能被误报为重名。
                    if (isDuplicateUsername(exception)) {
                        throw new UserAlreadyExistsException(UserAlreadyExistsException.USERNAME_ALREADY_EXISTS,
                                "用户名已占用");
                    }
                    throw exception;
                }
                var profile = users.findProfileSnapshot(actor.userId()).orElseThrow(CurrentUserRevokedException::new);
                return new CurrentUserResponse(profile.id(), profile.username(), profile.email(),
                        profile.userRole(), actor.loginRole(), profile.createdAt() == null ? null
                        : LocalDateTime.parse(profile.createdAt().replace(' ', 'T')).toInstant(ZoneOffset.UTC));
            });
        } catch (DataAccessException | TransactionException exception) {
            throw new UserProfileUpdateUnavailableException(exception);
        }
    }

    private boolean isDuplicateUsername(Throwable failure) {
        var pending = new ArrayDeque<Throwable>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof SQLException sql) {
                if (sql.getErrorCode() == 1062 && "23000".equals(sql.getSQLState())) {
                    return true;
                }
                if (sql.getNextException() != null) {
                    pending.add(sql.getNextException());
                }
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
        }
        return false;
    }
}

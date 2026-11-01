package org.example.music.user.service;

import java.time.ZoneOffset;
import java.time.LocalDateTime;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.dto.CurrentUserResponse;
import org.example.music.user.exception.CurrentUserRevokedException;
import org.example.music.user.exception.UserProfileStoreUnavailableException;
import org.example.music.user.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CurrentUserService {
    private final UserRepository userRepository;
    private final TransactionTemplate readTransaction;

    public CurrentUserService(UserRepository userRepository, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public CurrentUserResponse getCurrentUser(AuthenticatedUserPrincipal actor) {
        // 包围整个事务执行，确保启动和提交失败也遵循资料存储错误契约。
        try {
            return readTransaction.execute(status -> {
                var profile = userRepository.findProfileSnapshot(actor.userId())
                        .orElseThrow(CurrentUserRevokedException::new);
                return new CurrentUserResponse(profile.id(), profile.username(), profile.email(),
                        profile.userRole(), actor.loginRole(),
                        // DATETIME 存储 UTC 壁钟值；读取文本避免 JDBC Calendar 与 JVM 时区二次转换。
                        profile.createdAt() == null ? null : LocalDateTime.parse(
                                profile.createdAt().replace(' ', 'T')).toInstant(ZoneOffset.UTC));
            });
        } catch (DataAccessException | TransactionException exception) {
            throw new UserProfileStoreUnavailableException(exception);
        }
    }
}

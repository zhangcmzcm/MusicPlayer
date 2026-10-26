package org.example.music.user.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.example.music.security.UserTokenVersionService;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.domain.UserRoleHistoryEntity;
import org.example.music.user.dto.UserRoleUpdateResponse;
import org.example.music.user.exception.RoleUpdateForbiddenException;
import org.example.music.user.exception.SelfRoleModificationException;
import org.example.music.user.exception.UserNotFoundException;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.repository.UserRoleHistoryRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRoleAuthorizationService {

    private final UserRepository userRepository;
    private final UserRoleHistoryRepository userRoleHistoryRepository;
    private final UserTokenVersionService userTokenVersionService;

    public UserRoleAuthorizationService(
            UserRepository userRepository,
            UserRoleHistoryRepository userRoleHistoryRepository,
            UserTokenVersionService userTokenVersionService
    ) {
        this.userRepository = userRepository;
        this.userRoleHistoryRepository = userRoleHistoryRepository;
        this.userTokenVersionService = userTokenVersionService;
    }

    @Transactional
    public UserRoleUpdateResponse updateRole(
            Integer actorUserId,
            UserRole actorRole,
            Integer targetUserId,
            UserRole newRole
    ) {
        if (actorRole != UserRole.ADMIN) {
            throw new RoleUpdateForbiddenException();
        }
        if (actorUserId.equals(targetUserId)) {
            throw new SelfRoleModificationException();
        }

        UserEntity target = userRepository.findById(targetUserId)
                .orElseThrow(UserNotFoundException::new);
        UserRole previousRole = target.getUserRole();
        if (previousRole == newRole) {
            return new UserRoleUpdateResponse(target.getId(), target.getUsername(), target.getUserRole());
        }

        userTokenVersionService.increment(targetUserId);
        target.setUserRole(newRole);
        target.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userRepository.saveAndFlush(target);
        userRoleHistoryRepository.save(new UserRoleHistoryEntity(
                targetUserId,
                actorUserId,
                previousRole,
                newRole,
                java.time.Instant.now(),
                MDC.get("traceId")
        ));

        return new UserRoleUpdateResponse(target.getId(), target.getUsername(), target.getUserRole());
    }
}
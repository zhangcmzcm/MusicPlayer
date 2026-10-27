package org.example.music.user.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.domain.UserRoleHistoryEntity;
import org.example.music.user.dto.UserRoleUpdateResponse;
import org.example.music.user.exception.AdminPrivilegeRevokedException;
import org.example.music.user.exception.RoleUpdateForbiddenException;
import org.example.music.user.exception.LastAdminCannotBeDemotedException;
import org.example.music.user.exception.RoleUpdateConflictException;
import org.example.music.user.exception.SelfRoleModificationException;
import org.example.music.user.exception.UserNotFoundException;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.repository.UserRoleChangeGuardRepository;
import org.example.music.user.repository.UserRoleHistoryRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRoleAuthorizationService {

    private final UserRepository userRepository;
    private final UserRoleChangeGuardRepository userRoleChangeGuardRepository;
    private final UserRoleHistoryRepository userRoleHistoryRepository;

    public UserRoleAuthorizationService(
            UserRepository userRepository,
            UserRoleChangeGuardRepository userRoleChangeGuardRepository,
            UserRoleHistoryRepository userRoleHistoryRepository
    ) {
        this.userRepository = userRepository;
        this.userRoleChangeGuardRepository = userRoleChangeGuardRepository;
        this.userRoleHistoryRepository = userRoleHistoryRepository;
    }

    @Transactional
    public UserRoleUpdateResponse updateRole(
            Integer actorUserId,
            UserRole actorLoginRole,
            Integer targetUserId,
            UserRole newRole
    ) {
        if (actorLoginRole != UserRole.ADMIN) {
            throw new RoleUpdateForbiddenException();
        }
        userRoleChangeGuardRepository.findAndLock()
                .orElseThrow(() -> new IllegalStateException("User role change guard is missing"));

        UserEntity actor = userRepository.findAndLockById(actorUserId)
                .orElseThrow(UserNotFoundException::new);
        if (actor.getUserRole() != UserRole.ADMIN) {
            throw new AdminPrivilegeRevokedException();
        }

        UserEntity target = userRepository.findAndLockById(targetUserId)
                .orElseThrow(UserNotFoundException::new);
        if (java.util.Objects.equals(actorUserId, targetUserId)) {
            throw new SelfRoleModificationException();
        }

        UserRole previousRole = target.getUserRole();
        if (previousRole == newRole) {
            return new UserRoleUpdateResponse(target.getId(), target.getUsername(), target.getUserRole());
        }

        if (previousRole == UserRole.ADMIN && newRole == UserRole.USER
                && userRepository.lockAdminRows().size() <= 1) {
            throw new LastAdminCannotBeDemotedException();
        }

        Instant changedAt = Instant.now();
        int updated = userRepository.updateRoleAndIncrementTokenVersion(
                target.getId(),
                previousRole,
                newRole,
                LocalDateTime.ofInstant(changedAt, ZoneOffset.UTC)
        );
        if (updated != 1) {
            throw new RoleUpdateConflictException();
        }

        userRoleHistoryRepository.save(new UserRoleHistoryEntity(
                target.getId(),
                actor.getId(),
                previousRole,
                newRole,
                changedAt,
                MDC.get("traceId")
        ));

        return new UserRoleUpdateResponse(target.getId(), target.getUsername(), newRole);
    }
}

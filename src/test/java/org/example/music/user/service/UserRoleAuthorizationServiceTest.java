package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.domain.UserRoleChangeGuardEntity;
import org.example.music.user.domain.UserRoleHistoryEntity;
import org.example.music.user.exception.AdminPrivilegeRevokedException;
import org.example.music.user.exception.LastAdminCannotBeDemotedException;
import org.example.music.user.exception.RoleUpdateConflictException;
import org.example.music.user.exception.RoleUpdateForbiddenException;
import org.example.music.user.exception.SelfRoleModificationException;
import org.example.music.user.exception.UserNotFoundException;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.repository.UserRoleChangeGuardRepository;
import org.example.music.user.repository.UserRoleHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserRoleAuthorizationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserRoleChangeGuardRepository userRoleChangeGuardRepository;

    @Mock
    private UserRoleHistoryRepository userRoleHistoryRepository;

    @InjectMocks
    private UserRoleAuthorizationService service;

    @Test
    void should_update_role_and_write_history_for_other_user() {
        UserEntity actor = user(1, "admin", UserRole.ADMIN);
        UserEntity target = user(2, "target", UserRole.USER);
        lockGuard();
        when(userRepository.findAndLockById(1)).thenReturn(Optional.of(actor));
        when(userRepository.findAndLockById(2)).thenReturn(Optional.of(target));
        when(userRepository.updateRoleAndIncrementTokenVersion(
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq(UserRole.USER),
                org.mockito.ArgumentMatchers.eq(UserRole.ADMIN),
                any()
        )).thenReturn(1);

        var response = service.updateRole(1, UserRole.ADMIN, 2, UserRole.ADMIN);

        assertEquals(2, response.id());
        assertEquals(UserRole.ADMIN, response.role());
        ArgumentCaptor<UserRoleHistoryEntity> history = ArgumentCaptor.forClass(UserRoleHistoryEntity.class);
        verify(userRoleHistoryRepository).save(history.capture());
        assertEquals(UserRole.USER, history.getValue().getPreviousRole());
        assertEquals(UserRole.ADMIN, history.getValue().getNewRole());
        assertEquals(1, history.getValue().getActorUserId());
    }

    @Test
    void should_reject_when_login_role_is_not_admin() {
        assertThrows(RoleUpdateForbiddenException.class,
                () -> service.updateRole(1, UserRole.USER, 2, UserRole.ADMIN));
        verify(userRoleChangeGuardRepository, never()).findAndLock();
    }

    @Test
    void should_reject_when_database_role_is_not_admin() {
        lockGuard();
        when(userRepository.findAndLockById(1)).thenReturn(Optional.of(user(1, "actor", UserRole.USER)));

        assertThrows(AdminPrivilegeRevokedException.class,
                () -> service.updateRole(1, UserRole.ADMIN, 2, UserRole.ADMIN));
    }

    @Test
    void should_reject_missing_target_user() {
        lockGuard();
        when(userRepository.findAndLockById(1)).thenReturn(Optional.of(user(1, "admin", UserRole.ADMIN)));
        when(userRepository.findAndLockById(2)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class,
                () -> service.updateRole(1, UserRole.ADMIN, 2, UserRole.ADMIN));
    }

    @Test
    void should_reject_self_role_modification() {
        UserEntity actor = user(1, "admin", UserRole.ADMIN);
        lockGuard();
        when(userRepository.findAndLockById(1)).thenReturn(Optional.of(actor));

        assertThrows(SelfRoleModificationException.class,
                () -> service.updateRole(1, UserRole.ADMIN, 1, UserRole.USER));
    }

    @Test
    void should_not_increment_version_or_write_history_for_same_role() {
        lockGuard();
        when(userRepository.findAndLockById(1)).thenReturn(Optional.of(user(1, "admin", UserRole.ADMIN)));
        when(userRepository.findAndLockById(2)).thenReturn(Optional.of(user(2, "target", UserRole.USER)));

        var response = service.updateRole(1, UserRole.ADMIN, 2, UserRole.USER);

        assertEquals(UserRole.USER, response.role());
        verify(userRepository, never()).updateRoleAndIncrementTokenVersion(any(), any(), any(), any());
        verify(userRoleHistoryRepository, never()).save(any(UserRoleHistoryEntity.class));
    }

    @Test
    void should_reject_demoting_last_admin() {
        lockGuard();
        when(userRepository.findAndLockById(1)).thenReturn(Optional.of(user(1, "admin", UserRole.ADMIN)));
        when(userRepository.findAndLockById(2)).thenReturn(Optional.of(user(2, "last-admin", UserRole.ADMIN)));
        when(userRepository.lockAdminRows()).thenReturn(List.of(2));

        assertThrows(LastAdminCannotBeDemotedException.class,
                () -> service.updateRole(1, UserRole.ADMIN, 2, UserRole.USER));
        verify(userRepository, never()).updateRoleAndIncrementTokenVersion(any(), any(), any(), any());
    }

    @Test
    void should_reject_when_conditional_update_does_not_match() {
        lockGuard();
        when(userRepository.findAndLockById(1)).thenReturn(Optional.of(user(1, "admin", UserRole.ADMIN)));
        when(userRepository.findAndLockById(2)).thenReturn(Optional.of(user(2, "target", UserRole.USER)));
        when(userRepository.updateRoleAndIncrementTokenVersion(any(), any(), any(), any())).thenReturn(0);

        assertThrows(RoleUpdateConflictException.class,
                () -> service.updateRole(1, UserRole.ADMIN, 2, UserRole.ADMIN));
        verify(userRoleHistoryRepository, never()).save(any(UserRoleHistoryEntity.class));
    }

    private void lockGuard() {
        when(userRoleChangeGuardRepository.findAndLock())
                .thenReturn(Optional.of(new UserRoleChangeGuardEntity(1)));
    }

    private UserEntity user(Integer id, String username, UserRole role) {
        UserEntity user = new UserEntity(username, username + "@example.com", "hash", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}

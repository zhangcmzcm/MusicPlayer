package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.example.music.security.UserTokenVersionService;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.domain.UserRoleHistoryEntity;
import org.example.music.user.exception.RoleUpdateForbiddenException;
import org.example.music.user.exception.SelfRoleModificationException;
import org.example.music.user.exception.UserNotFoundException;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.repository.UserRoleHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserRoleAuthorizationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserRoleHistoryRepository userRoleHistoryRepository;

    @Mock
    private UserTokenVersionService userTokenVersionService;

    @InjectMocks
    private UserRoleAuthorizationService service;

    @Test
    void should_update_role_and_write_history_for_other_user() {
        UserEntity target = new UserEntity("target", null, "hash", UserRole.USER);
        ReflectionTestUtils.setField(target, "id", 2);
        when(userRepository.findById(2)).thenReturn(Optional.of(target));
        when(userRepository.saveAndFlush(target)).thenReturn(target);
        when(userTokenVersionService.increment(2)).thenReturn(1L);

        var response = service.updateRole(1, UserRole.ADMIN, 2, UserRole.ADMIN);

        assertEquals(2, response.id());
        assertEquals(UserRole.ADMIN, response.role());
        InOrder order = inOrder(userTokenVersionService, userRepository, userRoleHistoryRepository);
        order.verify(userTokenVersionService).increment(2);
        order.verify(userRepository).saveAndFlush(target);
        order.verify(userRoleHistoryRepository).save(any(UserRoleHistoryEntity.class));
    }

    @Test
    void should_reject_self_role_modification() {
        assertThrows(SelfRoleModificationException.class, () -> service.updateRole(1, UserRole.ADMIN, 1, UserRole.USER));
        verify(userRepository, never()).findById(any());
    }

    @Test
    void should_reject_non_admin_actor() {
        assertThrows(RoleUpdateForbiddenException.class, () -> service.updateRole(1, UserRole.USER, 2, UserRole.ADMIN));
    }

    @Test
    void should_reject_missing_target_user() {
        when(userRepository.findById(2)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> service.updateRole(1, UserRole.ADMIN, 2, UserRole.ADMIN));
    }

    @Test
    void should_not_revoke_tokens_or_write_history_for_same_role() {
        UserEntity target = new UserEntity("target", null, "hash", UserRole.USER);
        ReflectionTestUtils.setField(target, "id", 2);
        when(userRepository.findById(2)).thenReturn(Optional.of(target));

        var response = service.updateRole(1, UserRole.ADMIN, 2, UserRole.USER);

        assertEquals(UserRole.USER, response.role());
        verify(userTokenVersionService, never()).increment(2);
        verify(userRoleHistoryRepository, never()).save(any(UserRoleHistoryEntity.class));
    }
}
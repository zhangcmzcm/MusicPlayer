package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.example.music.security.PasswordHasher;
import org.example.music.user.config.AdminBootstrapProperties;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.domain.UserRoleChangeGuardEntity;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.repository.UserRoleChangeGuardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapInitializerTest {

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserRoleChangeGuardRepository userRoleChangeGuardRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void configureTransaction() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void should_create_bootstrap_admin_when_no_admin_exists() {
        AdminBootstrapProperties properties = properties(true);
        when(userRoleChangeGuardRepository.findAndLock())
                .thenReturn(Optional.of(new UserRoleChangeGuardEntity(1)));
        when(userRepository.countByUserRole(UserRole.ADMIN)).thenReturn(0L);
        when(userRepository.existsByUsername("zhangcm")).thenReturn(false);
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(passwordHasher.hash("raw-password")).thenReturn("bcrypt-hash");
        UserEntity saved = new UserEntity("zhangcm", "admin@example.com", "bcrypt-hash", UserRole.ADMIN);
        ReflectionTestUtils.setField(saved, "id", 7);
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenReturn(saved);

        initializer(properties).run(null);

        ArgumentCaptor<UserEntity> user = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(user.capture());
        assertEquals(UserRole.ADMIN, user.getValue().getUserRole());
        assertEquals("bcrypt-hash", user.getValue().getPasswordHash());
    }

    @Test
    void should_skip_bootstrap_when_admin_already_exists() {
        AdminBootstrapProperties properties = properties(true);
        when(userRoleChangeGuardRepository.findAndLock())
                .thenReturn(Optional.of(new UserRoleChangeGuardEntity(1)));
        when(userRepository.countByUserRole(UserRole.ADMIN)).thenReturn(1L);

        initializer(properties).run(null);

        verify(passwordHasher, never()).hash(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void should_fail_when_admin_is_missing_and_configuration_is_incomplete() {
        AdminBootstrapProperties properties = properties(true);
        properties.setUsername(null);
        when(userRoleChangeGuardRepository.findAndLock())
                .thenReturn(Optional.of(new UserRoleChangeGuardEntity(1)));
        when(userRepository.countByUserRole(UserRole.ADMIN)).thenReturn(0L);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> initializer(properties).run(null)
        );

        assertEquals("Missing required admin bootstrap configuration: ADMIN_BOOTSTRAP_USERNAME", exception.getMessage());
    }

    @Test
    void should_fail_when_bootstrap_username_is_already_used() {
        AdminBootstrapProperties properties = properties(true);
        when(userRoleChangeGuardRepository.findAndLock())
                .thenReturn(Optional.of(new UserRoleChangeGuardEntity(1)));
        when(userRepository.countByUserRole(UserRole.ADMIN)).thenReturn(0L);
        when(userRepository.existsByUsername("zhangcm")).thenReturn(true);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> initializer(properties).run(null)
        );

        assertEquals("Admin bootstrap username is already in use", exception.getMessage());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void should_skip_bootstrap_when_disabled() {
        AdminBootstrapProperties properties = properties(false);

        initializer(properties).run(null);

        verifyNoInteractions();
    }

    private AdminBootstrapProperties properties(boolean enabled) {
        AdminBootstrapProperties properties = new AdminBootstrapProperties();
        properties.setEnabled(enabled);
        properties.setUsername("zhangcm");
        properties.setEmail("admin@example.com");
        properties.setPassword("raw-password");
        return properties;
    }

    private AdminBootstrapInitializer initializer(AdminBootstrapProperties properties) {
        return new AdminBootstrapInitializer(
                properties,
                passwordHasher,
                userRepository,
                userRoleChangeGuardRepository,
                new TransactionTemplate(transactionManager)
        );
    }

    private void verifyNoInteractions() {
        verify(userRoleChangeGuardRepository, never()).findAndLock();
        verify(userRepository, never()).countByUserRole(any());
        verify(passwordHasher, never()).hash(any());
    }
}

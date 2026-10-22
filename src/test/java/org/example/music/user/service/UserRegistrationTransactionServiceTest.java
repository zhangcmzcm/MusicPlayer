package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.UserRegisterRequest;
import org.example.music.user.dto.UserRegisterResponse;
import org.example.music.user.exception.UserAlreadyExistsException;
import org.example.music.user.repository.UserRepository;
import org.example.music.verification.service.EmailVerificationCodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class UserRegistrationTransactionServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private EmailVerificationCodeService emailVerificationCodeService;

    @InjectMocks
    private UserRegistrationTransactionService service;

    @Test
    void should_create_user_with_role_user_without_email() {
        UserRegisterRequest request = new UserRegisterRequest(
                "alice_2026",
                "correct-password",
                null,
                null
        );
        when(userRepository.existsByUsername("alice_2026")).thenReturn(false);
        when(passwordHasher.hash("correct-password")).thenReturn("bcrypt-token");
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserRegisterResponse response = service.register(request);

        assertEquals("alice_2026", response.username());
        assertNull(response.email());
        assertEquals(UserRole.USER, response.userRole());

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertEquals("bcrypt-token", captor.getValue().getPasswordToken());
        verifyNoInteractions(emailVerificationCodeService);
    }

    @Test
    void should_consume_code_and_normalize_email_when_email_is_present() {
        UserRegisterRequest request = new UserRegisterRequest(
                "alice_2026",
                "correct-password",
                "Alice@Example.COM",
                "123456"
        );
        when(userRepository.existsByUsername("alice_2026")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordHasher.hash("correct-password")).thenReturn("bcrypt-token");
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserRegisterResponse response = service.register(request);

        assertEquals("alice@example.com", response.email());
        verify(emailVerificationCodeService).consume("alice@example.com", "123456");
    }

    @Test
    void should_throw_username_conflict_without_consuming_code() {
        UserRegisterRequest request = new UserRegisterRequest(
                "alice_2026",
                "correct-password",
                "alice@example.com",
                "123456"
        );
        when(userRepository.existsByUsername("alice_2026")).thenReturn(true);

        UserAlreadyExistsException exception = assertThrows(
                UserAlreadyExistsException.class,
                () -> service.register(request)
        );

        assertEquals(UserAlreadyExistsException.USERNAME_ALREADY_EXISTS, exception.getErrorCode());
        verifyNoInteractions(passwordHasher);
        verifyNoInteractions(emailVerificationCodeService);
    }

    @Test
    void should_translate_database_conflict() {
        UserRegisterRequest request = new UserRegisterRequest(
                "alice_2026",
                "correct-password",
                null,
                null
        );
        when(userRepository.existsByUsername("alice_2026")).thenReturn(false);
        when(passwordHasher.hash("correct-password")).thenReturn("bcrypt-token");
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        UserAlreadyExistsException exception = assertThrows(
                UserAlreadyExistsException.class,
                () -> service.register(request)
        );

        assertEquals(UserAlreadyExistsException.USER_ALREADY_EXISTS, exception.getErrorCode());
    }
}
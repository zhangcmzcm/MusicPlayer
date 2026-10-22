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
import org.example.music.verification.service.VerifiedEmailCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Mock
    private AfterCommitEmailVerificationCodeConsumer afterCommitEmailVerificationCodeConsumer;

    @InjectMocks
    private UserRegistrationTransactionService service;

    @Test
    void should_create_user_without_email_without_accessing_verification_code() {
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
        verifyNoInteractions(emailVerificationCodeService);
        verifyNoInteractions(afterCommitEmailVerificationCodeConsumer);
    }

    @Test
    void should_register_code_consumption_after_successful_save() {
        UserRegisterRequest request = new UserRegisterRequest(
                "alice_2026",
                "correct-password",
                "Alice@Example.COM",
                "123456"
        );
        VerifiedEmailCode verifiedEmailCode = new VerifiedEmailCode("alice@example.com", "code-version");
        when(userRepository.existsByUsername("alice_2026")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(emailVerificationCodeService.validate("alice@example.com", "123456")).thenReturn(verifiedEmailCode);
        when(passwordHasher.hash("correct-password")).thenReturn("bcrypt-token");
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserRegisterResponse response = service.register(request);

        assertEquals("alice@example.com", response.email());
        verify(afterCommitEmailVerificationCodeConsumer).registerAfterCommit(verifiedEmailCode);
    }

    @Test
    void should_not_register_consumption_when_database_write_fails() {
        UserRegisterRequest request = new UserRegisterRequest(
                "alice_2026",
                "correct-password",
                "alice@example.com",
                "123456"
        );
        VerifiedEmailCode verifiedEmailCode = new VerifiedEmailCode("alice@example.com", "code-version");
        when(userRepository.existsByUsername("alice_2026")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(emailVerificationCodeService.validate("alice@example.com", "123456")).thenReturn(verifiedEmailCode);
        when(passwordHasher.hash("correct-password")).thenReturn("bcrypt-token");
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed"));

        assertThrows(UserAlreadyExistsException.class, () -> service.register(request));

        verifyNoInteractions(afterCommitEmailVerificationCodeConsumer);
    }

    @Test
    void should_check_conflicts_before_email_code_validation() {
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
}
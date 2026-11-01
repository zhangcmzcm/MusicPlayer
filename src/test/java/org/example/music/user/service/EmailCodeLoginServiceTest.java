package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.example.music.user.domain.UserRole;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.exception.InvalidCredentialsException;
import org.example.music.user.repository.UserRepository;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.example.music.verification.exception.EmailVerificationCodeException;
import org.example.music.verification.service.EmailVerificationCodeService;
import org.example.music.verification.service.VerifiedEmailCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailCodeLoginServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailVerificationCodeService emailVerificationCodeService;

    @InjectMocks
    private EmailCodeLoginService service;

    @Test
    void should_consume_login_code_and_return_principal() {
        UserEntity user = new UserEntity("alice", "alice@example.com", "hash", org.example.music.user.domain.UserRole.USER);
        when(emailVerificationCodeService.validate("alice@example.com", "123456", VerificationCodePurpose.LOGIN))
                .thenReturn(new VerifiedEmailCode("alice@example.com", "version", VerificationCodePurpose.LOGIN));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(emailVerificationCodeService.consume(any(VerifiedEmailCode.class))).thenReturn(true);

        assertEquals("alice", service.authenticate("Alice@Example.COM", "123456").username());
        verify(emailVerificationCodeService).consume(any(VerifiedEmailCode.class));
    }

    @Test
    void should_reject_unknown_email_after_validating_code() {
        when(emailVerificationCodeService.validate(eq("alice@example.com"), eq("123456"), eq(VerificationCodePurpose.LOGIN)))
                .thenReturn(new VerifiedEmailCode("alice@example.com", "version", VerificationCodePurpose.LOGIN));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class, () -> service.authenticate("alice@example.com", "123456"));
    }

    @Test
    void should_translate_verification_failure_to_invalid_credentials() {
        when(emailVerificationCodeService.validate(eq("alice@example.com"), eq("654321"), eq(VerificationCodePurpose.LOGIN)))
                .thenThrow(new EmailVerificationCodeException(
                        EmailVerificationCodeException.Failure.INVALID,
                        "invalid"
                ));

        assertThrows(InvalidCredentialsException.class, () -> service.authenticate("alice@example.com", "654321"));
    }

    @Test
    void should_freeze_version_before_consuming_code() {
        UserEntity user = mock(UserEntity.class);
        AtomicLong version = new AtomicLong(7);
        when(user.getId()).thenReturn(42);
        when(user.getUsername()).thenReturn("alice");
        when(user.getEmail()).thenReturn("alice@example.com");
        when(user.getUserRole()).thenReturn(UserRole.USER);
        when(user.getTokenVersion()).thenAnswer(invocation -> version.get());
        VerifiedEmailCode code = new VerifiedEmailCode("alice@example.com", "version", VerificationCodePurpose.LOGIN);
        when(emailVerificationCodeService.validate("alice@example.com", "123456", VerificationCodePurpose.LOGIN))
                .thenReturn(code);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(emailVerificationCodeService.consume(code)).thenAnswer(invocation -> {
            version.incrementAndGet();
            return true;
        });
        assertEquals(7, service.authenticate("alice@example.com", "123456").tokenVersion());
        var order = org.mockito.Mockito.inOrder(userRepository, emailVerificationCodeService);
        order.verify(emailVerificationCodeService).validate("alice@example.com", "123456", VerificationCodePurpose.LOGIN);
        order.verify(userRepository).findByEmail("alice@example.com");
        order.verify(emailVerificationCodeService).consume(code);
    }

    @Test
    void should_reject_when_code_consumption_fails() {
        UserEntity user = new UserEntity("alice", "alice@example.com", "hash", UserRole.USER);
        VerifiedEmailCode code = new VerifiedEmailCode("alice@example.com", "version", VerificationCodePurpose.LOGIN);
        when(emailVerificationCodeService.validate("alice@example.com", "123456", VerificationCodePurpose.LOGIN))
                .thenReturn(code);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(emailVerificationCodeService.consume(code)).thenReturn(false);
        assertThrows(InvalidCredentialsException.class, () -> service.authenticate("alice@example.com", "123456"));
    }
}

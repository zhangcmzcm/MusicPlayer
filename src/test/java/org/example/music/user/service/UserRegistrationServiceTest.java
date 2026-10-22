package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.example.music.user.dto.UserRegisterRequest;
import org.example.music.verification.exception.EmailVerificationCodeException;
import org.example.music.verification.service.EmailVerificationCodeFailureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserRegistrationServiceTest {

    @Mock
    private UserRegistrationTransactionService userRegistrationTransactionService;

    @Mock
    private EmailVerificationCodeFailureService emailVerificationCodeFailureService;

    @InjectMocks
    private UserRegistrationService service;

    private final UserRegisterRequest request = new UserRegisterRequest(
            "alice_2026",
            "correct-password",
            "alice@example.com",
            "123456"
    );

    @Test
    void should_record_invalid_code_attempt_in_separate_transaction() {
        EmailVerificationCodeException invalid = invalidCodeException();
        when(userRegistrationTransactionService.register(request)).thenThrow(invalid);
        when(emailVerificationCodeFailureService.recordFailure(10L)).thenReturn(1);

        EmailVerificationCodeException exception = assertThrows(
                EmailVerificationCodeException.class,
                () -> service.register(request)
        );

        assertEquals(EmailVerificationCodeException.Failure.INVALID, exception.getFailure());
        verify(emailVerificationCodeFailureService).recordFailure(10L);
    }

    @Test
    void should_promote_invalid_code_to_attempts_exhausted_at_limit() {
        EmailVerificationCodeException invalid = invalidCodeException();
        when(userRegistrationTransactionService.register(request)).thenThrow(invalid);
        when(emailVerificationCodeFailureService.recordFailure(10L)).thenReturn(5);

        EmailVerificationCodeException exception = assertThrows(
                EmailVerificationCodeException.class,
                () -> service.register(request)
        );

        assertEquals(EmailVerificationCodeException.Failure.ATTEMPTS_EXCEEDED, exception.getFailure());
    }

    @Test
    void should_not_record_expired_code_as_failed_attempt() {
        EmailVerificationCodeException expired = new EmailVerificationCodeException(
                EmailVerificationCodeException.Failure.EXPIRED,
                10L,
                "expired"
        );
        when(userRegistrationTransactionService.register(request)).thenThrow(expired);

        assertThrows(EmailVerificationCodeException.class, () -> service.register(request));

        verifyNoInteractions(emailVerificationCodeFailureService);
    }

    private EmailVerificationCodeException invalidCodeException() {
        return new EmailVerificationCodeException(
                EmailVerificationCodeException.Failure.INVALID,
                10L,
                "invalid"
        );
    }
}
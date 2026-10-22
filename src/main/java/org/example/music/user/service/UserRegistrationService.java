package org.example.music.user.service;

import org.example.music.user.dto.UserRegisterRequest;
import org.example.music.user.dto.UserRegisterResponse;
import org.example.music.verification.exception.EmailVerificationCodeException;
import org.example.music.verification.service.EmailVerificationCodeFailureService;
import org.springframework.stereotype.Service;

@Service
public class UserRegistrationService {

    private static final int MAX_ATTEMPTS = 5;

    private final UserRegistrationTransactionService userRegistrationTransactionService;
    private final EmailVerificationCodeFailureService emailVerificationCodeFailureService;

    public UserRegistrationService(
            UserRegistrationTransactionService userRegistrationTransactionService,
            EmailVerificationCodeFailureService emailVerificationCodeFailureService
    ) {
        this.userRegistrationTransactionService = userRegistrationTransactionService;
        this.emailVerificationCodeFailureService = emailVerificationCodeFailureService;
    }

    public UserRegisterResponse register(UserRegisterRequest request) {
        try {
            return userRegistrationTransactionService.register(request);
        } catch (EmailVerificationCodeException exception) {
            if (exception.getFailure() != EmailVerificationCodeException.Failure.INVALID
                    || exception.getVerificationCodeId() == null) {
                throw exception;
            }

            int attempts = emailVerificationCodeFailureService.recordFailure(exception.getVerificationCodeId());
            if (attempts >= MAX_ATTEMPTS) {
                throw new EmailVerificationCodeException(
                        EmailVerificationCodeException.Failure.ATTEMPTS_EXCEEDED,
                        exception.getVerificationCodeId(),
                        "Email verification code attempts are exhausted"
                );
            }
            throw exception;
        }
    }
}

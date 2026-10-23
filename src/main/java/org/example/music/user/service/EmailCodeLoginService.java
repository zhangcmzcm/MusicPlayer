package org.example.music.user.service;

import java.util.Locale;
import org.example.music.user.domain.LoginPrincipal;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.exception.InvalidCredentialsException;
import org.example.music.user.repository.UserRepository;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.example.music.verification.exception.EmailVerificationCodeException;
import org.example.music.verification.service.EmailVerificationCodeService;
import org.example.music.verification.service.VerifiedEmailCode;
import org.springframework.stereotype.Service;

@Service
public class EmailCodeLoginService {

    private final UserRepository userRepository;
    private final EmailVerificationCodeService emailVerificationCodeService;

    public EmailCodeLoginService(
            UserRepository userRepository,
            EmailVerificationCodeService emailVerificationCodeService
    ) {
        this.userRepository = userRepository;
        this.emailVerificationCodeService = emailVerificationCodeService;
    }

    public LoginPrincipal authenticate(String email, String code) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        VerifiedEmailCode verifiedEmailCode;
        try {
            verifiedEmailCode = emailVerificationCodeService.validate(
                    normalizedEmail,
                    code,
                    VerificationCodePurpose.LOGIN
            );
        } catch (EmailVerificationCodeException exception) {
            throw new InvalidCredentialsException();
        }

        UserEntity user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(InvalidCredentialsException::new);
        if (!emailVerificationCodeService.consume(verifiedEmailCode)) {
            throw new InvalidCredentialsException();
        }

        return new LoginPrincipal(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getUserRole()
        );
    }
}
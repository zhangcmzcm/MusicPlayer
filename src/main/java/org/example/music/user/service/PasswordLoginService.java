package org.example.music.user.service;

import java.util.Locale;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.LoginPrincipal;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.dto.LoginType;
import org.example.music.user.exception.InvalidCredentialsException;
import org.example.music.user.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class PasswordLoginService {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;

    public PasswordLoginService(UserRepository userRepository, PasswordHasher passwordHasher) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
    }

    public LoginPrincipal authenticate(
            LoginType loginType,
            String account,
            String password
    ) {
        UserEntity user = switch (loginType) {
            case USERNAME_PASSWORD -> userRepository.findByUsername(account.trim())
                    .orElseThrow(InvalidCredentialsException::new);
            case EMAIL_PASSWORD -> userRepository.findByEmail(normalizeEmail(account))
                    .orElseThrow(InvalidCredentialsException::new);
            default -> throw new InvalidCredentialsException();
        };

        if (!passwordHasher.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return principal(user);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private LoginPrincipal principal(UserEntity user) {
        return new LoginPrincipal(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getUserRole()
        );
    }
}
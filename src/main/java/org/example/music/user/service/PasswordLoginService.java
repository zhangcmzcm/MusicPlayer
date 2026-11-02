package org.example.music.user.service;

import java.util.Locale;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.example.music.user.domain.UserAuthorizationSnapshot;
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
    private final Clock clock;

    public PasswordLoginService(UserRepository userRepository, PasswordHasher passwordHasher,
            @Qualifier("authenticationClock") Clock clock) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
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

        UserAuthorizationSnapshot snapshot = snapshot(user);
        if (!passwordHasher.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return new LoginPrincipal(snapshot.userId(), snapshot.username(), snapshot.email(), snapshot.userRole(),
                snapshot.tokenVersion(), clock.instant().truncatedTo(ChronoUnit.SECONDS));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private UserAuthorizationSnapshot snapshot(UserEntity user) {
        return new UserAuthorizationSnapshot(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getUserRole(),
                user.getTokenVersion()
        );
    }
}

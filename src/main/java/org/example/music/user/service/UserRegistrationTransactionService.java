package org.example.music.user.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.UserRegisterRequest;
import org.example.music.user.dto.UserRegisterResponse;
import org.example.music.user.exception.UserAlreadyExistsException;
import org.example.music.user.repository.UserRepository;
import org.example.music.verification.service.EmailVerificationCodeService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRegistrationTransactionService {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final EmailVerificationCodeService emailVerificationCodeService;

    public UserRegistrationTransactionService(
            UserRepository userRepository,
            PasswordHasher passwordHasher,
            EmailVerificationCodeService emailVerificationCodeService
    ) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.emailVerificationCodeService = emailVerificationCodeService;
    }

    @Transactional
    public UserRegisterResponse register(UserRegisterRequest request) {
        String username = request.username().trim();
        String email = normalizeEmail(request.email());

        if (userRepository.existsByUsername(username)) {
            throw new UserAlreadyExistsException(
                    UserAlreadyExistsException.USERNAME_ALREADY_EXISTS,
                    "Username is already registered"
            );
        }

        if (email != null && userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException(
                    UserAlreadyExistsException.EMAIL_ALREADY_EXISTS,
                    "Email is already registered"
            );
        }

        if (email != null) {
            emailVerificationCodeService.consume(email, request.code());
        }

        UserEntity user = UserEntity.create(
                username,
                email,
                passwordHasher.hash(request.password())
        );

        UserEntity savedUser;
        try {
            savedUser = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new UserAlreadyExistsException(
                    UserAlreadyExistsException.USER_ALREADY_EXISTS,
                    "User already exists"
            );
        }

        return new UserRegisterResponse(
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getEmail(),
                UserRole.USER,
                savedUser.getCreatedAt().toInstant(ZoneOffset.UTC)
        );
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}

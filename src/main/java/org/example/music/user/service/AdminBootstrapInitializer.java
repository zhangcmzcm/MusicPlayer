package org.example.music.user.service;

import org.example.music.security.PasswordHasher;
import org.example.music.user.config.AdminBootstrapProperties;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.repository.UserRoleChangeGuardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.transaction.support.TransactionTemplate;

public class AdminBootstrapInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminBootstrapInitializer.class);

    private final AdminBootstrapProperties properties;
    private final PasswordHasher passwordHasher;
    private final UserRepository userRepository;
    private final UserRoleChangeGuardRepository userRoleChangeGuardRepository;
    private final TransactionTemplate transactionTemplate;

    public AdminBootstrapInitializer(
            AdminBootstrapProperties properties,
            PasswordHasher passwordHasher,
            UserRepository userRepository,
            UserRoleChangeGuardRepository userRoleChangeGuardRepository,
            TransactionTemplate transactionTemplate
    ) {
        this.properties = properties;
        this.passwordHasher = passwordHasher;
        this.userRepository = userRepository;
        this.userRoleChangeGuardRepository = userRoleChangeGuardRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> initialize());
    }

    private void initialize() {
        userRoleChangeGuardRepository.findAndLock()
                .orElseThrow(() -> new IllegalStateException("User role change guard is missing"));

        if (userRepository.countByUserRole(UserRole.ADMIN) > 0) {
            return;
        }

        String username = requiredValue("ADMIN_BOOTSTRAP_USERNAME", properties.getUsername());
        String email = requiredValue("ADMIN_BOOTSTRAP_EMAIL", properties.getEmail());
        String password = requiredValue("ADMIN_BOOTSTRAP_PASSWORD", properties.getPassword());

        if (userRepository.existsByUsername(username)) {
            throw new IllegalStateException("Admin bootstrap username is already in use");
        }
        if (userRepository.existsByEmail(email)) {
            throw new IllegalStateException("Admin bootstrap email is already in use");
        }

        UserEntity admin = userRepository.saveAndFlush(UserEntity.createAdmin(
                username,
                email,
                passwordHasher.hash(password)
        ));
        LOGGER.info("Bootstrap administrator created, userId={}", admin.getId());
    }

    private String requiredValue(String environmentVariable, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required admin bootstrap configuration: " + environmentVariable);
        }
        return value;
    }
}

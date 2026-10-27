package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.example.music.security.PasswordHasher;
import org.example.music.user.config.AdminBootstrapProperties;
import org.example.music.user.domain.UserRole;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.repository.UserRoleHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
class AdminBootstrapIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");

    @Autowired
    @Qualifier("adminBootstrapInitializer")
    private ApplicationRunner initializer;

    @Autowired
    private AdminBootstrapProperties properties;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleHistoryRepository userRoleHistoryRepository;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void reset() {
        userRoleHistoryRepository.deleteAll();
        userRepository.deleteAll();
        properties.setEnabled(true);
        properties.setUsername("zhangcm");
        properties.setEmail("admin@example.com");
        properties.setPassword("raw-password");
    }

    @Test
    void should_create_one_bcrypt_admin_when_initialized_twice() throws Exception {
        DefaultApplicationArguments args = new DefaultApplicationArguments();

        initializer.run(args);
        initializer.run(args);

        assertEquals(1, userRepository.countByUserRole(UserRole.ADMIN));
        var admin = userRepository.findByUsername("zhangcm").orElseThrow();
        assertTrue(passwordHasher.matches("raw-password", admin.getPasswordHash()));
    }

    @Test
    void should_create_one_admin_when_initializers_run_concurrently() throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> runInitializer(startTogether));
            Future<?> second = executor.submit(() -> runInitializer(startTogether));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, userRepository.countByUserRole(UserRole.ADMIN));
    }

    @Test
    void should_fail_when_admin_bootstrap_configuration_is_missing() {
        userRepository.save(new org.example.music.user.domain.UserEntity(
                "existing-user",
                "existing@example.com",
                "hash",
                UserRole.USER
        ));
        properties.setUsername(null);

        assertThrows(
                IllegalStateException.class,
                () -> initializer.run(new DefaultApplicationArguments())
        );
    }

    private void runInitializer(CyclicBarrier startTogether) {
        try {
            startTogether.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Bootstrap initializer barrier failed", exception);
        }
        try {
            initializer.run(new DefaultApplicationArguments());
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Bootstrap initializer failed", exception);
        }
    }
}

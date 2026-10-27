package org.example.music.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.example.music.security.JwtTokenService;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.service.UserRoleAuthorizationService;
import org.example.music.user.repository.UserRoleHistoryRepository;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.music.user.exception.AdminPrivilegeRevokedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
class UserRoleAuthorizationIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleHistoryRepository userRoleHistoryRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private UserRoleAuthorizationService userRoleAuthorizationService;

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
    }

    @Test
    void should_update_role_write_history_and_revoke_target_old_token() throws Exception {
        UserEntity admin = userRepository.save(new UserEntity("admin", "admin@example.com", "hash", UserRole.ADMIN));
        UserEntity target = userRepository.save(new UserEntity("target", "target@example.com", "hash", UserRole.USER));
        String adminToken = issueToken(admin, UserRole.ADMIN);
        String targetToken = issueToken(target, UserRole.USER);

        mockMvc.perform(patch("/api/v1/users/" + target.getId() + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.getId()))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        assertEquals(1, userRoleHistoryRepository.findAll().size());

        mockMvc.perform(patch("/api/v1/users/" + target.getId() + "/role")
                        .header("Authorization", "Bearer " + targetToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
    }

    @Test
    void should_keep_at_least_one_admin_when_two_admins_demote_each_other() throws Exception {
        UserEntity adminOne = userRepository.save(new UserEntity("admin-one", "one@example.com", "hash", UserRole.ADMIN));
        UserEntity adminTwo = userRepository.save(new UserEntity("admin-two", "two@example.com", "hash", UserRole.ADMIN));
        CyclicBarrier startTogether = new CyclicBarrier(2);
        AtomicInteger successes = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> demote(startTogether, adminOne.getId(), adminTwo.getId(), successes));
            Future<?> second = executor.submit(() -> demote(startTogether, adminTwo.getId(), adminOne.getId(), successes));

            assertConcurrentDemotion(first);
            assertConcurrentDemotion(second);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, successes.get());
        assertEquals(1, userRepository.countByUserRole(UserRole.ADMIN));
        assertEquals(1, userRoleHistoryRepository.findAll().size());
    }

    private void demote(
            CyclicBarrier startTogether,
            Integer actorId,
            Integer targetId,
            AtomicInteger successes
    ) {
        try {
            startTogether.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Role update barrier failed", exception);
        }
        try {
            userRoleAuthorizationService.updateRole(actorId, UserRole.ADMIN, targetId, UserRole.USER);
            successes.incrementAndGet();
        } catch (AdminPrivilegeRevokedException expected) {
            // The other administrator was demoted first, so this actor no longer has permission.
        }
    }

    private void assertConcurrentDemotion(Future<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException exception) {
            assertTrue(exception.getCause() instanceof AdminPrivilegeRevokedException, exception.toString());
        }
    }

    private String issueToken(UserEntity user, UserRole loginRole) {
        return jwtTokenService.issue(new UserAuthorizationSnapshot(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getUserRole(),
                user.getTokenVersion()
        ), loginRole, LoginType.USERNAME_PASSWORD).tokenValue();
    }
}

package org.example.music.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.example.music.security.JwtTokenService;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.track.service.TrackPublishService;
import org.example.music.track.service.TrackPublishTransactionService;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
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
class TrackPublishIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private TrackPublishService trackPublishService;

    @MockitoSpyBean
    private TrackPublishTransactionService trackPublishTransactionService;

    @Autowired
    private JwtTokenService jwtTokenService;

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("spring.data.redis.database", () -> "0");
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.timeout", () -> "2s");
    }

    @BeforeEach
    void resetDatabase() {
        reset(trackPublishTransactionService);
        trackRepository.deleteAll();
        mediaAssetRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void should_publish_draft_when_admin_uses_real_bearer_token() throws Exception {
        UserEntity admin = saveUser("publish-admin", UserRole.ADMIN);
        TrackEntity track = saveDraft(admin);
        String token = issueToken(admin, UserRole.ADMIN);

        mockMvc.perform(post("/api/v1/tracks/" + track.getId() + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(track.getId()))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        TrackEntity persisted = trackRepository.findById(track.getId()).orElseThrow();
        assertEquals(TrackStatus.PUBLISHED, persisted.getStatus());
        assertEquals(1L, persisted.getVersion());
        assertEquals(admin.getId(), persisted.getUpdatedBy());
        assertNotNull(persisted.getUpdatedAt());
    }

    @Test
    void should_return_409_and_preserve_version_when_published_track_is_published_again() throws Exception {
        UserEntity admin = saveUser("publish-repeat-admin", UserRole.ADMIN);
        TrackEntity track = saveDraft(admin);
        String token = issueToken(admin, UserRole.ADMIN);
        trackPublishService.publish(track.getId(), admin.getId());
        TrackEntity published = trackRepository.findById(track.getId()).orElseThrow();
        Long version = published.getVersion();
        Instant updatedAt = published.getUpdatedAt().toInstant(ZoneOffset.UTC);
        Integer updatedBy = published.getUpdatedBy();

        mockMvc.perform(post("/api/v1/tracks/" + track.getId() + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_DRAFT"));

        TrackEntity persisted = trackRepository.findById(track.getId()).orElseThrow();
        assertEquals(version, persisted.getVersion());
        assertEquals(updatedAt, persisted.getUpdatedAt().toInstant(ZoneOffset.UTC));
        assertEquals(updatedBy, persisted.getUpdatedBy());
    }

    @Test
    void should_return_409_without_version_fields_when_concurrent_update_wins() throws Exception {
        UserEntity admin = saveUser("publish-conflict-admin", UserRole.ADMIN);
        TrackEntity track = saveDraft(admin);
        String token = issueToken(admin, UserRole.ADMIN);
        LocalDateTime concurrentUpdatedAt = LocalDateTime.of(2026, 10, 27, 12, 0);
        Integer concurrentUserId = saveUser("publish-conflict-editor", UserRole.ADMIN).getId();

        TrackEntity stale = detachedTrack(track, 0L);
        TrackEntity current = trackRepository.findById(track.getId()).orElseThrow();
        current.updateDraft(
                "Concurrent title",
                "Concurrent artist",
                null,
                1,
                1,
                concurrentUpdatedAt,
                concurrentUserId
        );
        trackRepository.saveAndFlush(current);

        doAnswer(invocation -> {
            assertThrows(
                    ObjectOptimisticLockingFailureException.class,
                    () -> trackRepository.saveAndFlush(stale)
            );
            throw new ObjectOptimisticLockingFailureException(TrackEntity.class, track.getId());
        }).when(trackPublishTransactionService).publish(any(), anyInt());

        try {
            mockMvc.perform(post("/api/v1/tracks/" + track.getId() + "/publish")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:problem:music:track_version_conflict"))
                    .andExpect(jsonPath("$.code").value("TRACK_VERSION_CONFLICT"))
                    .andExpect(jsonPath("$.expectedVersion").doesNotExist())
                    .andExpect(jsonPath("$.actualVersion").doesNotExist());

            TrackEntity persisted = trackRepository.findById(track.getId()).orElseThrow();
            assertEquals(TrackStatus.DRAFT, persisted.getStatus());
            assertEquals(1L, persisted.getVersion());
            assertEquals(concurrentUpdatedAt, persisted.getUpdatedAt());
            assertEquals(concurrentUserId, persisted.getUpdatedBy());
        } finally {
            reset(trackPublishTransactionService);
        }
    }

    @Test
    void should_return_401_for_publish_without_authentication() throws Exception {
        TrackEntity track = saveDraft(saveUser("publish-anonymous-admin", UserRole.ADMIN));

        mockMvc.perform(post("/api/v1/tracks/" + track.getId() + "/publish"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_return_403_for_publish_when_user_is_not_admin() throws Exception {
        UserEntity user = saveUser("publish-user", UserRole.USER);
        TrackEntity track = saveDraft(saveUser("publish-target-admin", UserRole.ADMIN));
        String token = issueToken(user, UserRole.USER);

        mockMvc.perform(post("/api/v1/tracks/" + track.getId() + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private UserEntity saveUser(String username, UserRole role) {
        return userRepository.save(new UserEntity(
                username,
                username + "@example.com",
                "password-hash",
                role
        ));
    }

    private TrackEntity saveDraft(UserEntity user) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MediaAssetEntity audio = mediaAssetRepository.save(MediaAssetEntity.create(
                MediaAssetKind.AUDIO,
                "music-audio-publish-it",
                "publish/audio.mp3",
                "audio/mpeg",
                "audio.mp3",
                10L,
                "a".repeat(64),
                now,
                user.getId()
        ));
        return trackRepository.save(TrackEntity.createDraft(
                "Original title",
                "Original artist",
                null,
                215430L,
                1,
                1,
                audio.getId(),
                null,
                "{}",
                now,
                user.getId()
        ));
    }

    private TrackEntity detachedTrack(TrackEntity source, Long version) {
        TrackEntity stale = TrackEntity.createDraft(
                source.getTitle(),
                source.getArtistName(),
                source.getAlbumName(),
                source.getDurationMs(),
                source.getTrackNumber(),
                source.getDiscNumber(),
                source.getAudioAssetId(),
                source.getCoverAssetId(),
                source.getId3Metadata(),
                source.getCreatedAt(),
                source.getCreatedBy()
        );
        ReflectionTestUtils.setField(stale, "id", source.getId());
        ReflectionTestUtils.setField(stale, "version", version);
        return stale;
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

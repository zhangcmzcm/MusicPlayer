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
import org.example.music.track.service.TrackArchiveService;
import org.example.music.track.service.TrackArchiveTransactionService;
import org.example.music.track.service.TrackPublishService;
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
class TrackArchiveIntegrationTest {

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
    private TrackArchiveService trackArchiveService;

    @MockitoSpyBean
    private TrackArchiveTransactionService trackArchiveTransactionService;

    @Autowired
    private TrackPublishService trackPublishService;

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
        reset(trackArchiveTransactionService);
        trackRepository.deleteAll();
        mediaAssetRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void should_archive_draft_when_admin_uses_real_bearer_token() throws Exception {
        UserEntity admin = saveUser("archive-draft-admin", UserRole.ADMIN);
        TrackEntity track = saveDraft(admin);
        Long audioAssetId = track.getAudioAssetId();
        String token = issueToken(admin, UserRole.ADMIN);

        mockMvc.perform(post("/api/v1/tracks/" + track.getId() + "/archive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(track.getId()))
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        TrackEntity persisted = trackRepository.findById(track.getId()).orElseThrow();
        assertEquals(TrackStatus.ARCHIVED, persisted.getStatus());
        assertEquals(1L, persisted.getVersion());
        assertEquals(admin.getId(), persisted.getUpdatedBy());
        assertNotNull(persisted.getUpdatedAt());
        assertNotNull(mediaAssetRepository.findById(audioAssetId).orElse(null));
    }

    @Test
    void should_archive_published_track_when_admin_uses_real_bearer_token() throws Exception {
        UserEntity admin = saveUser("archive-published-admin", UserRole.ADMIN);
        TrackEntity draft = saveDraft(admin);
        trackPublishService.publish(draft.getId(), admin.getId());
        TrackEntity track = trackRepository.findById(draft.getId()).orElseThrow();
        String token = issueToken(admin, UserRole.ADMIN);

        mockMvc.perform(post("/api/v1/tracks/" + track.getId() + "/archive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.version").value(2));

        TrackEntity persisted = trackRepository.findById(track.getId()).orElseThrow();
        assertEquals(TrackStatus.ARCHIVED, persisted.getStatus());
        assertEquals(2L, persisted.getVersion());
        assertEquals(admin.getId(), persisted.getUpdatedBy());
    }

    @Test
    void should_return_409_and_preserve_fields_when_archived_track_is_archived_again() throws Exception {
        UserEntity admin = saveUser("archive-repeat-admin", UserRole.ADMIN);
        TrackEntity track = saveDraft(admin);
        String token = issueToken(admin, UserRole.ADMIN);
        trackArchiveService.archive(track.getId(), admin.getId());
        TrackEntity archived = trackRepository.findById(track.getId()).orElseThrow();
        Long version = archived.getVersion();
        Instant updatedAt = archived.getUpdatedAt().toInstant(ZoneOffset.UTC);
        Integer updatedBy = archived.getUpdatedBy();

        mockMvc.perform(post("/api/v1/tracks/" + track.getId() + "/archive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:music:track_already_archived"))
                .andExpect(jsonPath("$.code").value("TRACK_ALREADY_ARCHIVED"));

        TrackEntity persisted = trackRepository.findById(track.getId()).orElseThrow();
        assertEquals(version, persisted.getVersion());
        assertEquals(updatedAt, persisted.getUpdatedAt().toInstant(ZoneOffset.UTC));
        assertEquals(updatedBy, persisted.getUpdatedBy());
    }

    @Test
    void should_return_409_without_version_fields_when_concurrent_update_wins() throws Exception {
        UserEntity admin = saveUser("archive-conflict-admin", UserRole.ADMIN);
        TrackEntity track = saveDraft(admin);
        String token = issueToken(admin, UserRole.ADMIN);
        LocalDateTime concurrentUpdatedAt = LocalDateTime.of(2026, 10, 29, 12, 0);
        Integer concurrentUserId = saveUser("archive-conflict-editor", UserRole.ADMIN).getId();

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
            stale.archive(concurrentUserId, concurrentUpdatedAt);
            assertThrows(
                    ObjectOptimisticLockingFailureException.class,
                    () -> trackRepository.saveAndFlush(stale)
            );
            throw new ObjectOptimisticLockingFailureException(TrackEntity.class, track.getId());
        }).when(trackArchiveTransactionService).archive(any(), anyInt());

        try {
            mockMvc.perform(post("/api/v1/tracks/" + track.getId() + "/archive")
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
            reset(trackArchiveTransactionService);
        }
    }

    @Test
    void should_return_401_for_archive_without_authentication() throws Exception {
        TrackEntity track = saveDraft(saveUser("archive-anonymous-admin", UserRole.ADMIN));

        mockMvc.perform(post("/api/v1/tracks/" + track.getId() + "/archive"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_return_403_for_archive_when_user_is_not_admin() throws Exception {
        UserEntity user = saveUser("archive-user", UserRole.USER);
        TrackEntity track = saveDraft(saveUser("archive-target-admin", UserRole.ADMIN));
        String token = issueToken(user, UserRole.USER);

        mockMvc.perform(post("/api/v1/tracks/" + track.getId() + "/archive")
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
                "music-audio-archive-it",
                "archive/audio.mp3",
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
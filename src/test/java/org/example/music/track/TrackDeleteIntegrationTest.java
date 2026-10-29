package org.example.music.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.example.music.security.JwtTokenService;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.storage.exception.AudioObjectNotFoundException;
import org.example.music.storage.exception.AudioStorageUnavailableException;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.MediaImportEntity;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackDeleteFailedException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.MediaImportRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.track.service.TrackDeleteTransactionService;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MINIO_INTEGRATION_TESTS", matches = "true")
class TrackDeleteIntegrationTest {

    private static final String AUDIO_BUCKET = "music-audio-track-delete-it";
    private static final String COVERS_BUCKET = "music-covers-track-delete-it";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4"))
            .withExposedPorts(6379);

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("bitnamilegacy/minio@sha256:451fe6858cb770cc9d0e77ba811ce287420f781c7c1b806a386f6896471a349c")
    )
            .withEnv("MINIO_ROOT_USER", "test-access-key")
            .withEnv("MINIO_ROOT_PASSWORD", "test-secret-key")
            .withExposedPorts(9000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private MediaImportRepository mediaImportRepository;

    @Autowired
    private TrackRepository trackRepository;

    @MockitoSpyBean
    private AudioStorage audioStorage;

    @MockitoSpyBean
    private TrackDeleteTransactionService trackDeleteTransactionService;

    @Autowired
    private JwtTokenService jwtTokenService;

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> "0");
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.timeout", () -> "2s");
        registry.add("app.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("app.storage.region", () -> "us-east-1");
        registry.add("app.storage.access-key", () -> "test-access-key");
        registry.add("app.storage.secret-key", () -> "test-secret-key");
        registry.add("app.storage.audio-bucket", () -> AUDIO_BUCKET);
        registry.add("app.storage.covers-bucket", () -> COVERS_BUCKET);
        registry.add("app.track-import.temp-dir", () -> System.getProperty("java.io.tmpdir") + "/music-track-delete-it");
        registry.add("app.track-import.audio-max-bytes", () -> String.valueOf(200L * 1024 * 1024));
        registry.add("app.track-import.cover-max-bytes", () -> String.valueOf(20L * 1024 * 1024));
        registry.add("app.track-import.rate-limit-per-minute", () -> "10");
    }

    @BeforeAll
    static void createBuckets() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")
                ))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(AUDIO_BUCKET).build());
            client.createBucket(CreateBucketRequest.builder().bucket(COVERS_BUCKET).build());
        }
    }

    @BeforeEach
    void resetDatabase() {
        reset(audioStorage, trackDeleteTransactionService);
        mediaImportRepository.deleteAll();
        trackRepository.deleteAll();
        mediaAssetRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void should_delete_archived_track_database_records_and_minio_objects() throws Exception {
        UserEntity admin = saveUser("delete-admin", UserRole.ADMIN);
        TrackFixture fixture = saveTrack(admin, TrackStatus.ARCHIVED, true);
        String token = issueToken(admin, UserRole.ADMIN);

        mockMvc.perform(delete("/api/v1/tracks/" + fixture.track().getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertNull(trackRepository.findById(fixture.track().getId()).orElse(null));
        assertNull(mediaAssetRepository.findById(fixture.audio().getId()).orElse(null));
        assertNull(mediaAssetRepository.findById(fixture.cover().getId()).orElse(null));
        assertThrows(AudioObjectNotFoundException.class, () -> audioStorage.head(AUDIO_BUCKET, fixture.audio().getObjectKey()));
        assertThrows(AudioObjectNotFoundException.class, () -> audioStorage.head(COVERS_BUCKET, fixture.cover().getObjectKey()));

        MediaImportEntity importEntity = mediaImportRepository.findById(fixture.importEntity().getId()).orElseThrow();
        assertNull(importEntity.getTrackId());
    }

    @Test
    void should_keep_database_and_objects_when_draft_is_deleted() throws Exception {
        UserEntity admin = saveUser("delete-draft-admin", UserRole.ADMIN);
        TrackFixture fixture = saveTrack(admin, TrackStatus.DRAFT, true);
        String token = issueToken(admin, UserRole.ADMIN);

        mockMvc.perform(delete("/api/v1/tracks/" + fixture.track().getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_ARCHIVED"));

        assertNotNull(trackRepository.findById(fixture.track().getId()).orElse(null));
        assertNotNull(mediaAssetRepository.findById(fixture.audio().getId()).orElse(null));
        assertNotNull(audioStorage.head(AUDIO_BUCKET, fixture.audio().getObjectKey()));
    }

    @Test
    void should_keep_database_and_objects_when_published_track_is_deleted() throws Exception {
        UserEntity admin = saveUser("delete-published-admin", UserRole.ADMIN);
        TrackFixture fixture = saveTrack(admin, TrackStatus.PUBLISHED, true);
        String token = issueToken(admin, UserRole.ADMIN);

        mockMvc.perform(delete("/api/v1/tracks/" + fixture.track().getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_ARCHIVED"));

        assertNotNull(trackRepository.findById(fixture.track().getId()).orElse(null));
        assertNotNull(audioStorage.head(AUDIO_BUCKET, fixture.audio().getObjectKey()));
    }

    @Test
    void should_return_404_when_archived_track_is_deleted_again() throws Exception {
        UserEntity admin = saveUser("delete-repeat-admin", UserRole.ADMIN);
        TrackFixture fixture = saveTrack(admin, TrackStatus.ARCHIVED, true);
        String token = issueToken(admin, UserRole.ADMIN);

        mockMvc.perform(delete("/api/v1/tracks/" + fixture.track().getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/tracks/" + fixture.track().getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
    }

    @Test
    void should_delete_track_when_media_objects_are_already_missing() throws Exception {
        UserEntity admin = saveUser("delete-missing-object-admin", UserRole.ADMIN);
        TrackFixture fixture = saveTrack(admin, TrackStatus.ARCHIVED, false);
        String token = issueToken(admin, UserRole.ADMIN);

        mockMvc.perform(delete("/api/v1/tracks/" + fixture.track().getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertNull(trackRepository.findById(fixture.track().getId()).orElse(null));
    }

    @Test
    void should_keep_database_when_media_cleanup_fails() throws Exception {
        UserEntity admin = saveUser("delete-cleanup-failure-admin", UserRole.ADMIN);
        TrackFixture fixture = saveTrack(admin, TrackStatus.ARCHIVED, true);
        String token = issueToken(admin, UserRole.ADMIN);
        AudioStorageUnavailableException failure =
                new AudioStorageUnavailableException("storage down", new RuntimeException("connection refused"));
        doThrow(failure).when(audioStorage).delete(anyString(), anyString());

        mockMvc.perform(delete("/api/v1/tracks/" + fixture.track().getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRACK_MEDIA_CLEANUP_FAILED"));

        assertNotNull(trackRepository.findById(fixture.track().getId()).orElse(null));
        assertNotNull(mediaAssetRepository.findById(fixture.audio().getId()).orElse(null));
    }

    @Test
    void should_keep_database_when_database_delete_fails_then_succeed_on_retry() throws Exception {
        UserEntity admin = saveUser("delete-db-failure-admin", UserRole.ADMIN);
        TrackFixture fixture = saveTrack(admin, TrackStatus.ARCHIVED, true);
        String token = issueToken(admin, UserRole.ADMIN);
        doThrow(new TrackDeleteFailedException(fixture.track().getId(), new RuntimeException("db down")))
                .when(trackDeleteTransactionService).delete(any(), any());

        mockMvc.perform(delete("/api/v1/tracks/" + fixture.track().getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRACK_DELETE_FAILED"));

        assertNotNull(trackRepository.findById(fixture.track().getId()).orElse(null));
        reset(trackDeleteTransactionService);

        mockMvc.perform(delete("/api/v1/tracks/" + fixture.track().getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertNull(trackRepository.findById(fixture.track().getId()).orElse(null));
    }

    @Test
    void should_return_401_without_authentication() throws Exception {
        UserEntity admin = saveUser("delete-anonymous-admin", UserRole.ADMIN);
        TrackFixture fixture = saveTrack(admin, TrackStatus.ARCHIVED, true);

        mockMvc.perform(delete("/api/v1/tracks/" + fixture.track().getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_return_403_when_user_is_not_admin() throws Exception {
        UserEntity user = saveUser("delete-user", UserRole.USER);
        UserEntity admin = saveUser("delete-target-admin", UserRole.ADMIN);
        TrackFixture fixture = saveTrack(admin, TrackStatus.ARCHIVED, true);
        String token = issueToken(user, UserRole.USER);

        mockMvc.perform(delete("/api/v1/tracks/" + fixture.track().getId())
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

    private TrackFixture saveTrack(UserEntity user, TrackStatus status, boolean uploadObjects) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MediaAssetEntity audio = mediaAssetRepository.save(MediaAssetEntity.create(
                MediaAssetKind.AUDIO,
                AUDIO_BUCKET,
                "delete/" + UUID.randomUUID() + "/audio.mp3",
                "audio/mpeg",
                "audio.mp3",
                5L,
                "a".repeat(64),
                now,
                user.getId()
        ));
        MediaAssetEntity cover = mediaAssetRepository.save(MediaAssetEntity.create(
                MediaAssetKind.COVER,
                COVERS_BUCKET,
                "delete/" + UUID.randomUUID() + "/cover.jpg",
                "image/jpeg",
                "cover.jpg",
                5L,
                "b".repeat(64),
                now,
                user.getId()
        ));
        if (uploadObjects) {
            putObject(audio);
            putObject(cover);
        }

        TrackEntity track = TrackEntity.createDraft(
                "Delete title",
                "Delete artist",
                null,
                1000L,
                1,
                1,
                audio.getId(),
                cover.getId(),
                "{}",
                now,
                user.getId()
        );
        ReflectionTestUtils.setField(track, "status", status);
        track = trackRepository.save(track);

        MediaImportEntity importEntity = mediaImportRepository.save(MediaImportEntity.start(
                UUID.randomUUID(),
                "audio.mp3",
                "audio/mpeg",
                now,
                user.getId()
        ));
        importEntity.ready(track.getId(), now);
        mediaImportRepository.save(importEntity);

        return new TrackFixture(track, audio, cover, importEntity);
    }

    private void putObject(MediaAssetEntity asset) {
        byte[] content = "audio".getBytes(StandardCharsets.UTF_8);
        audioStorage.put(
                asset.getBucket(),
                asset.getObjectKey(),
                new ByteArrayInputStream(content),
                content.length,
                asset.getContentType(),
                asset.getSha256()
        );
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

    private record TrackFixture(
            TrackEntity track,
            MediaAssetEntity audio,
            MediaAssetEntity cover,
            MediaImportEntity importEntity
    ) {
    }
}
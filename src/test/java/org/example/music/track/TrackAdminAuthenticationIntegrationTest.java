package org.example.music.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.example.music.security.JwtTokenService;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.MediaImportRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.track.service.AudioTestFixtures;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
class TrackAdminAuthenticationIntegrationTest {

    private static final String AUDIO_BUCKET = "music-audio-track-auth-it";
    private static final String COVERS_BUCKET = "music-covers-track-auth-it";

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

    @TempDir
    Path tempDir;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private MediaImportRepository mediaImportRepository;

    @Autowired
    private AudioStorage audioStorage;

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
        registry.add("app.track-import.temp-dir", () -> System.getProperty("java.io.tmpdir") + "/music-track-auth-it");
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
    void reset() {
        mediaImportRepository.deleteAll();
        trackRepository.deleteAll();
        mediaAssetRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void should_update_draft_when_admin_uses_real_bearer_token() throws Exception {
        UserEntity admin = saveUser("auth-admin-draft", UserRole.ADMIN);
        TrackEntity track = saveDraft(admin);
        String token = issueToken(admin, UserRole.ADMIN);

        mockMvc.perform(patch("/api/v1/tracks/" + track.getId() + "/draft")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated title\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated title"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        assertEquals("Updated title", trackRepository.findById(track.getId()).orElseThrow().getTitle());
    }

    @Test
    void should_return_401_for_patch_without_authentication() throws Exception {
        mockMvc.perform(patch("/api/v1/tracks/1/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated title\",\"version\":0}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_return_403_for_patch_when_user_is_not_admin() throws Exception {
        UserEntity user = saveUser("auth-user-draft", UserRole.USER);
        String token = issueToken(user, UserRole.USER);

        mockMvc.perform(patch("/api/v1/tracks/1/draft")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated title\",\"version\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void should_import_track_when_admin_uses_real_bearer_token() throws Exception {
        UserEntity admin = saveUser("auth-admin-import", UserRole.ADMIN);
        String token = issueToken(admin, UserRole.ADMIN);
        Path source = AudioTestFixtures.taggedMp3(tempDir);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "import.mp3",
                "audio/mpeg",
                Files.readAllBytes(source)
        );
        UUID uploadId = UUID.randomUUID();

        mockMvc.perform(multipart("/api/v1/track-imports")
                        .file(file)
                        .param("uploadId", uploadId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadId").value(uploadId.toString()))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.track.status").value("DRAFT"));

        MediaAssetEntity audio = mediaAssetRepository.findAll().stream()
                .filter(asset -> asset.getKind() == MediaAssetKind.AUDIO)
                .findFirst()
                .orElseThrow();
        assertNotNull(audioStorage.head(audio.getBucket(), audio.getObjectKey()));
    }

    @Test
    void should_return_401_for_import_without_authentication() throws Exception {
        mockMvc.perform(multipart("/api/v1/track-imports")
                        .file(new MockMultipartFile("file", "import.mp3", "audio/mpeg", new byte[] {'I', 'D', '3'}))
                        .param("uploadId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_return_403_for_import_when_user_is_not_admin() throws Exception {
        UserEntity user = saveUser("auth-user-import", UserRole.USER);
        String token = issueToken(user, UserRole.USER);

        mockMvc.perform(multipart("/api/v1/track-imports")
                        .file(new MockMultipartFile("file", "import.mp3", "audio/mpeg", new byte[] {'I', 'D', '3'}))
                        .param("uploadId", UUID.randomUUID().toString())
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
                AUDIO_BUCKET,
                "auth-draft/audio.mp3",
                "audio/mpeg",
                "audio.mp3",
                10L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
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
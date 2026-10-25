package org.example.music.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.example.music.storage.S3AudioStorage;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.track.domain.MediaImportStatus;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.MediaImportRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.track.service.MediaImportService;
import org.example.music.track.service.TrackImportResult;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.repository.UserRepository;
import org.example.music.track.service.AudioTestFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
@EnabledIfEnvironmentVariable(named = "MINIO_INTEGRATION_TESTS", matches = "true")
class MediaImportIntegrationTest {

    private static final String AUDIO_BUCKET = "music-audio-import-it";
    private static final String COVERS_BUCKET = "music-covers-import-it";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");

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
    private MediaImportService mediaImportService;

    @Autowired
    private MediaImportRepository mediaImportRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AudioStorage audioStorage;

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
        registry.add("app.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("app.storage.region", () -> "us-east-1");
        registry.add("app.storage.access-key", () -> "test-access-key");
        registry.add("app.storage.secret-key", () -> "test-secret-key");
        registry.add("app.storage.audio-bucket", () -> AUDIO_BUCKET);
        registry.add("app.storage.covers-bucket", () -> COVERS_BUCKET);
        registry.add("app.track-import.temp-dir", () -> System.getProperty("java.io.tmpdir") + "/music-import-it");
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

    @Test
    void should_create_draft_and_media_assets_when_audio_import_succeeds() throws Exception {
        UserEntity user = userRepository.save(new UserEntity(
                "import-admin",
                "import-admin@example.com",
                "password-hash",
                org.example.music.user.domain.UserRole.ADMIN
        ));
        Path source = AudioTestFixtures.taggedMp3(tempDir);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "import.mp3",
                "audio/mpeg",
                Files.readAllBytes(source)
        );
        UUID uploadId = UUID.randomUUID();

        TrackImportResult result = mediaImportService.importTrack(uploadId, file, user.getId());

        assertTrue(result.created());
        assertEquals(MediaImportStatus.READY, result.importEntity().getStatus());
        assertEquals(TrackStatus.DRAFT, result.track().getStatus());
        assertNotNull(result.track().getAudioAssetId());
        assertNotNull(result.track().getCoverAssetId());
        assertEquals(2, mediaAssetRepository.count());
        assertEquals(1, trackRepository.count());
        assertEquals(1, mediaImportRepository.count());
        assertTrue(mediaImportRepository.findByUploadId(uploadId).isPresent());
    }
}

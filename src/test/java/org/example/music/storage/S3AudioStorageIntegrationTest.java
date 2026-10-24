package org.example.music.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.example.music.storage.exception.AudioObjectNotFoundException;
import org.example.music.storage.exception.InvalidAudioRangeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@Testcontainers
@EnabledIfDockerAvailable
@EnabledIfEnvironmentVariable(named = "MINIO_INTEGRATION_TESTS", matches = "true")
class S3AudioStorageIntegrationTest {

    private static final String BUCKET = "music-audio-test";

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("bitnamilegacy/minio@sha256:451fe6858cb770cc9d0e77ba811ce287420f781c7c1b806a386f6896471a349c")
    )
            .withEnv("MINIO_ROOT_USER", "test-access-key")
            .withEnv("MINIO_ROOT_PASSWORD", "test-secret-key")
            .withExposedPorts(9000);

    private S3Client s3Client;
    private S3AudioStorage storage;

    @BeforeEach
    void setUp() {
        s3Client = S3Client.builder()
                .endpointOverride(URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
        storage = new S3AudioStorage(s3Client);
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException exception) {
            // Shared static MinIO container intentionally reuses the test bucket.
        }
    }

    @AfterEach
    void tearDown() {
        s3Client.close();
    }

    @Test
    void should_store_and_read_object_when_request_is_valid() throws Exception {
        byte[] content = "audio-content".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256(content);

        storage.put(BUCKET, "audio/test.mp3", new ByteArrayInputStream(content), content.length, "audio/mpeg", sha256);

        try (var range = storage.read(BUCKET, "audio/test.mp3")) {
            assertEquals("audio/mpeg", range.contentType());
            assertEquals(content.length, range.contentLength());
            assertEquals(sha256, range.sha256());
            assertArrayEquals(content, range.content().readNBytes(content.length));
        }
    }

    @Test
    void should_read_only_requested_bytes_when_range_is_valid() throws Exception {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        storage.put(
                BUCKET,
                "audio/range.mp3",
                new ByteArrayInputStream(content),
                content.length,
                "audio/mpeg",
                sha256(content)
        );

        try (var range = storage.readRange(BUCKET, "audio/range.mp3", 2, 5)) {
            assertEquals(2, range.rangeStart());
            assertEquals(5, range.rangeEnd());
            assertEquals(4, range.contentLength());
            assertEquals(content.length, range.totalSize());
            assertArrayEquals("2345".getBytes(StandardCharsets.UTF_8), range.content().readNBytes(4));
        }
    }

    @Test
    void should_throw_invalid_range_when_range_is_out_of_bounds() {
        byte[] content = "short".getBytes(StandardCharsets.UTF_8);
        storage.put(
                BUCKET,
                "audio/invalid-range.mp3",
                new ByteArrayInputStream(content),
                content.length,
                "audio/mpeg",
                sha256(content)
        );

        assertThrows(
                InvalidAudioRangeException.class,
                () -> storage.readRange(BUCKET, "audio/invalid-range.mp3", 1, content.length)
        );
    }

    @Test
    void should_throw_not_found_when_object_does_not_exist() {
        assertThrows(
                AudioObjectNotFoundException.class,
                () -> storage.head(BUCKET, "audio/missing.mp3")
        );
    }

    @Test
    void should_delete_object_idempotently_when_object_is_already_absent() {
        storage.delete(BUCKET, "audio/already-deleted.mp3");
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}

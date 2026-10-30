package org.example.music.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.example.music.security.JwtTokenService;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.storage.domain.AudioObjectRange;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
@EnabledIfEnvironmentVariable(named = "MINIO_INTEGRATION_TESTS", matches = "true")
class TrackCoverIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("music");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
    @Container static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
            "bitnamilegacy/minio@sha256:451fe6858cb770cc9d0e77ba811ce287420f781c7c1b806a386f6896471a349c"))
            .withEnv("MINIO_ROOT_USER", "test-access-key").withEnv("MINIO_ROOT_PASSWORD", "test-secret-key").withExposedPorts(9000);
    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired TrackRepository tracks;
    @Autowired MediaAssetRepository assets;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired JdbcTemplate jdbc;
    @Autowired S3Client s3;
    @MockitoSpyBean AudioStorage storage;
    @LocalServerPort int port;
    private UserEntity admin;
    private UserEntity owner;
    private TrackEntity track;
    private String key;
    private byte[] bytes;
    private String digest;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
        registry.add("app.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("app.storage.audio-bucket", () -> "cover-test-audio");
        registry.add("app.storage.covers-bucket", () -> "cover-test-images");
        registry.add("app.storage.connect-timeout", () -> "1s");
        registry.add("app.storage.read-timeout", () -> "3s");
    }

    @BeforeEach
    void setup() throws Exception {
        reset(storage);
        tracks.deleteAll();
        assets.deleteAll();
        userRepository.deleteAll();
        if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals("cover-test-images"))) {
            s3.createBucket(b -> b.bucket("cover-test-images"));
        }
        admin = userRepository.save(new UserEntity("cover-admin", "cover-admin@example.com", "hash", UserRole.ADMIN));
        owner = userRepository.save(new UserEntity("cover-owner", "cover-owner@example.com", "hash", UserRole.USER));
        seed("png");
    }

    @AfterEach
    void cleanup() {
        reset(storage);
    }

    private void seed(String format) throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/cover/" + (format.equals("large") ? "large.png" : "cover." + format))) {
            bytes = java.util.Objects.requireNonNull(fixture).readAllBytes();
        }
        digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        key = "covers/" + java.util.UUID.randomUUID() + "." + format;
        String type = format.equals("jpg") ? "image/jpeg" : format.equals("large") ? "image/png" : "image/" + format;
        storage.put("cover-test-images", key, new ByteArrayInputStream(bytes), bytes.length, type, digest);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        var audio = assets.save(MediaAssetEntity.create(MediaAssetKind.AUDIO, "cover-test-audio", key + ".mp3", "audio/mpeg", "a.mp3", 1L, "a".repeat(64), now, owner.getId()));
        var cover = assets.save(MediaAssetEntity.create(MediaAssetKind.COVER, "cover-test-images", key, type, "cover." + format, (long) bytes.length, digest, now, owner.getId()));
        track = tracks.save(TrackEntity.createDraft("title", "artist", null, null, null, null, audio.getId(), cover.getId(), "{}", now, owner.getId()));
        jdbc.update("update tracks set status='PUBLISHED' where id=?", track.getId());
    }

    private String path() { return "/api/v1/tracks/" + track.getId() + "/cover"; }
    private String bearer(UserEntity user, UserRole role) { return "Bearer " + issueToken(user, role); }

    @ParameterizedTest
    @ValueSource(strings = {"jpg", "png", "webp"})
    void should_read_real_images_with_correct_headers_and_no_transaction_during_storage(String format) throws Exception {
        seed(format);
        doAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return i.callRealMethod();
        }).when(storage).head(anyString(), anyString());
        doAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return i.callRealMethod();
        }).when(storage).read(anyString(), anyString());
        mvc.perform(get(path()).header("Authorization", bearer(owner, UserRole.USER)).header("Range", "bytes=1-2"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Length", String.valueOf(bytes.length)))
                .andExpect(header().string("ETag", "\"" + digest + "\""))
                .andExpect(header().string("Cache-Control", "private, no-cache"))
                .andExpect(header().doesNotExist("Content-Range"));
        clearInvocations(storage);
        mvc.perform(get(path()).header("Authorization", bearer(owner, UserRole.USER)).header("If-None-Match", "W/\"" + digest + "\""))
                .andExpect(status().isNotModified()).andExpect(content().string(""));
        verify(storage, never()).read(anyString(), anyString());
        verify(storage).head("cover-test-images", key);
        assertEquals(0L, tracks.findById(track.getId()).orElseThrow().getVersion());
    }

    @ParameterizedTest
    @EnumSource(TrackStatus.class)
    void should_enforce_visibility_using_real_jwt_including_owner_and_admin_user_login(TrackStatus state) throws Exception {
        jdbc.update("update tracks set status=? where id=?", state.name(), track.getId());
        mvc.perform(get(path()).header("Authorization", bearer(admin, UserRole.ADMIN))).andExpect(status().isOk());
        for (UserEntity user : new UserEntity[]{owner, admin}) {
            var result = mvc.perform(get(path()).header("Authorization", bearer(user, UserRole.USER)).header("If-None-Match", "*"));
            if (state == TrackStatus.PUBLISHED) {
                result.andExpect(status().isNotModified());
            } else {
                result.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
            }
        }
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
    }

    @Test
    void should_reject_revoked_token_even_with_matching_etag() throws Exception {
        String token = bearer(owner, UserRole.USER);
        jdbc.update("update users set token_version=token_version+1 where id=?", owner.getId());
        mvc.perform(get(path()).header("Authorization", token).header("If-None-Match", "*"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
    }

    @Test
    void should_return_cover_not_found_when_reference_is_empty_or_object_is_deleted() throws Exception {
        storage.delete("cover-test-images", key);
        mvc.perform(get(path()).header("Authorization", bearer(owner, UserRole.USER)).header("If-None-Match", "*"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COVER_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Cover not found")).andExpect(header().string("Cache-Control", "no-store"));
        jdbc.update("update tracks set cover_asset_id=null where id=?", track.getId());
        mvc.perform(get(path()).header("Authorization", bearer(owner, UserRole.USER))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COVER_NOT_FOUND"));
    }

    @Test
    void should_reject_wrong_database_asset_kind() throws Exception {
        jdbc.update("update tracks set cover_asset_id=audio_asset_id where id=?", track.getId());
        mvc.perform(get(path()).header("Authorization", bearer(owner, UserRole.USER)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mime", "size", "digest", "missing-digest", "invalid-digest"})
    void should_not_return_304_for_inconsistent_real_object(String field) throws Exception {
        String type = field.equals("mime") ? "image/jpeg" : "image/png";
        byte[] changed = field.equals("size") ? new byte[]{1} : bytes;
        var metadata = field.equals("missing-digest") ? java.util.Map.<String,String>of()
                : java.util.Map.of("sha256", field.equals("digest") ? "b".repeat(64) : field.equals("invalid-digest") ? "invalid" : digest);
        s3.putObject(b -> b.bucket("cover-test-images").key(key).contentType(type).metadata(metadata),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(changed));
        mvc.perform(get(path()).header("Authorization", bearer(owner, UserRole.USER)).header("If-None-Match", "*"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("COVER_STORAGE_INCONSISTENT"))
                .andExpect(header().doesNotExist("ETag"));
    }

    @Test
    void should_not_return_304_when_minio_is_unavailable() throws Exception {
        MINIO.getDockerClient().pauseContainerCmd(MINIO.getContainerId()).exec();
        try {
            mvc.perform(get(path()).header("Authorization", bearer(owner, UserRole.USER)).header("If-None-Match", "*"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MEDIA_STORAGE_UNAVAILABLE"));
        } finally {
            MINIO.getDockerClient().unpauseContainerCmd(MINIO.getContainerId()).exec();
        }
    }

    private HttpURLConnection connect() throws Exception {
        var connection = (HttpURLConnection) URI.create("http://localhost:" + port + path()).toURL().openConnection();
        connection.setRequestProperty("Authorization", bearer(owner, UserRole.USER));
        connection.setReadTimeout(5000);
        connection.setConnectTimeout(3000);
        return connection;
    }

    @Test
    void should_stream_real_minio_bytes_over_http() throws Exception {
        var connection = connect();
        try {
            assertEquals(200, connection.getResponseCode());
            try (var input = connection.getInputStream()) { assertArrayEquals(bytes, input.readAllBytes()); }
        } finally { connection.disconnect(); }
    }

    @Test
    @Timeout(30)
    void should_abort_committed_http_response_without_json_and_release_stream() throws Exception {
        seed("large");
        var closed = new CountDownLatch(1);
        doAnswer(i -> {
            AudioObjectRange original = (AudioObjectRange) i.callRealMethod();
            var wrapped = new FilterInputStream(original.content()) {
                private int count;
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (count >= 16384) { throw new IOException("injected midstream failure"); }
                    int n = super.read(b, off, Math.min(len, 8192));
                    if (n > 0) { count += n; }
                    return n;
                }
                @Override public void close() throws IOException {
                    try { super.close(); } finally { closed.countDown(); }
                }
            };
            return new AudioObjectRange(wrapped, original.contentType(), original.contentLength(), original.rangeStart(), original.rangeEnd(), original.totalSize(), original.sha256());
        }).when(storage).read("cover-test-images", key);
        var connection = connect();
        var received = new ByteArrayOutputStream();
        try {
            assertEquals(200, connection.getResponseCode());
            try (var input = connection.getInputStream()) {
                input.transferTo(received);
            } catch (IOException expected) {
                // The fixed-length response must end early, not become a JSON success/error body.
            }
            assertTrue(received.size() > 0);
            assertTrue(received.size() < bytes.length);
            assertArrayEquals(java.util.Arrays.copyOf(bytes, received.size()), received.toByteArray());
            assertTrue(closed.await(5, TimeUnit.SECONDS));
        } finally { connection.disconnect(); }
    }

    @Test
    @Timeout(30)
    void should_release_source_after_real_http_client_disconnects() throws Exception {
        seed("large");
        var closed = new CountDownLatch(1);
        doAnswer(i -> {
            AudioObjectRange original = (AudioObjectRange) i.callRealMethod();
            var wrapped = new FilterInputStream(original.content()) {
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
                    return super.read(b, off, len);
                }
                @Override public void close() throws IOException {
                    try { super.close(); } finally { closed.countDown(); }
                }
            };
            return new AudioObjectRange(wrapped, original.contentType(), original.contentLength(), original.rangeStart(), original.rangeEnd(), original.totalSize(), original.sha256());
        }).when(storage).read("cover-test-images", key);
        var connection = connect();
        try (var input = connection.getInputStream()) { assertTrue(input.readNBytes(1024).length > 0); }
        finally { connection.disconnect(); }
        assertTrue(closed.await(5, TimeUnit.SECONDS));
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
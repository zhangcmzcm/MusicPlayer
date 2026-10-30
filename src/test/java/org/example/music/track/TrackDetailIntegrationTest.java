package org.example.music.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.example.music.security.JwtTokenService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
class TrackDetailIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("music");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TrackRepository trackRepository;
    @Autowired
    private MediaAssetRepository mediaAssetRepository;
    @Autowired
    private JwtTokenService jwtTokenService;
    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
    }

    @BeforeEach
    void resetDatabase() {
        trackRepository.deleteAll();
        mediaAssetRepository.deleteAll();
        userRepository.deleteAll();
    }

    @ParameterizedTest
    @EnumSource(TrackStatus.class)
    void should_apply_visibility_with_real_tokens_including_owner(TrackStatus state) throws Exception {
        UserEntity owner = saveUser("detail-owner", UserRole.USER);
        UserEntity admin = saveUser("detail-admin", UserRole.ADMIN);
        TrackEntity track = saveDraft(owner);
        jdbc.update("update tracks set status = ? where id = ?", state.name(), track.getId());
        String url = "/api/v1/tracks/" + track.getId();
        mockMvc.perform(get(url).header("Authorization", "Bearer " + issueToken(admin, UserRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(state.name()))
                .andExpect(header().string("Cache-Control", "no-store"));
        for (String token : new String[]{issueToken(owner, UserRole.USER), issueToken(admin, UserRole.USER)}) {
            var result = mockMvc.perform(get(url).header("Authorization", "Bearer " + token));
            if (state == TrackStatus.PUBLISHED) {
                result.andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Original title"));
            } else {
                result.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"))
                        .andExpect(jsonPath("$.detail").value("Track not found: " + track.getId()));
            }
        }
        assertEquals(0L, trackRepository.findById(track.getId()).orElseThrow().getVersion());
        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
    }

    @Test
    void should_read_cover_and_preserve_database_on_repeated_requests() throws Exception {
        UserEntity admin = saveUser("detail-cover", UserRole.ADMIN);
        TrackEntity track = saveDraft(admin);
        MediaAssetEntity cover = mediaAssetRepository.save(MediaAssetEntity.create(MediaAssetKind.COVER,
                "private-covers", "private.jpg", "image/jpeg", "cover.jpg", 123L, "b".repeat(64), LocalDateTime.now(), admin.getId()));
        jdbc.update("update tracks set cover_asset_id = ?, album_name = ? where id = ?", cover.getId(), "专辑", track.getId());
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/v1/tracks/" + track.getId()).header("Authorization", "Bearer " + issueToken(admin, UserRole.ADMIN)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.albumName").value("专辑"))
                    .andExpect(jsonPath("$.durationMs").value(215430))
                    .andExpect(jsonPath("$.cover.url").value("/api/v1/tracks/" + track.getId() + "/cover"))
                    .andExpect(jsonPath("$.cover.sizeBytes").value(123))
                    .andExpect(jsonPath("$.cover.contentType").value("image/jpeg"))
                    .andExpect(jsonPath("$.cover.bucket").doesNotExist())
                    .andExpect(jsonPath("$.id3Metadata").doesNotExist());
        }
        assertEquals(0L, trackRepository.findById(track.getId()).orElseThrow().getVersion());
        assertEquals(2, mediaAssetRepository.count());
    }

    @Test
    void should_reject_revoked_token() throws Exception {
        UserEntity admin = saveUser("detail-revoke", UserRole.ADMIN);
        String token = issueToken(admin, UserRole.ADMIN);
        jdbc.update("update users set token_version = token_version + 1 where id = ?", admin.getId());
        mockMvc.perform(get("/api/v1/tracks/42").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
    }

    @Test
    void should_return_not_found_for_missing_track_with_real_token() throws Exception {
        UserEntity user = saveUser("detail-missing", UserRole.USER);
        mockMvc.perform(get("/api/v1/tracks/9223372036854775807").header("Authorization", "Bearer " + issueToken(user, UserRole.USER)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
    }

    @Test
    void should_return_internal_error_for_wrong_cover_kind_in_database() throws Exception {
        UserEntity admin = saveUser("detail-inconsistent", UserRole.ADMIN);
        TrackEntity track = saveDraft(admin);
        jdbc.update("update tracks set cover_asset_id = ? where id = ?", track.getAudioAssetId(), track.getId());
        mockMvc.perform(get("/api/v1/tracks/" + track.getId()).header("Authorization", "Bearer " + issueToken(admin, UserRole.ADMIN)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void should_reject_malformed_bearer_token() throws Exception {
        mockMvc.perform(get("/api/v1/tracks/42").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("invalid_token")));
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
package org.example.music.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;
import org.example.music.security.JwtTokenService;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.repository.UserRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

@SpringBootTest(properties = {"app.track-search.query-timeout-ms=200", "spring.datasource.hikari.maximum-pool-size=1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
@Timeout(20)
class TrackSearchTimeoutIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TrackRepository tracks;
    @Autowired MediaAssetRepository assets;
    @Autowired JwtTokenService tokens;
    private String bearer;

    public static class SlowSearchInspector implements StatementInspector {
        private static final ThreadLocal<String> TARGET = new ThreadLocal<>();
        private static final ThreadLocal<String> MODIFIED = new ThreadLocal<>();

        @Override
        public String inspect(String sql) {
            String lowered = sql.toLowerCase(Locale.ROOT);
            String target = TARGET.get();
            boolean contentQuery = lowered.contains(" from tracks t join ");
            boolean countQuery = lowered.contains("count(*) from tracks i");
            if (lowered.contains("max_execution_time(200)")
                    && (("CONTENT".equals(target) && contentQuery) || ("COUNT".equals(target) && countQuery))) {
                TARGET.remove();
                String modified = sql.replaceFirst(" where ", " where SLEEP(10)=0 and ");
                MODIFIED.set(modified);
                return modified;
            }
            return sql;
        }

        static void clear() {
            TARGET.remove();
            MODIFIED.remove();
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector", () -> SlowSearchInspector.class.getName());
    }

    @BeforeEach
    void setup() {
        SlowSearchInspector.clear();
        tracks.deleteAll();
        assets.deleteAll();
        users.deleteAll();
        UserEntity actor = users.save(new UserEntity("search-timeout", "search-timeout@example.com", "test-hash", UserRole.USER));
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
        var audio = assets.save(MediaAssetEntity.create(MediaAssetKind.AUDIO, "test", "audio", "audio/mpeg",
                "audio.mp3", 1L, "a".repeat(64), now, actor.getId()));
        var track = TrackEntity.createDraft("晴天", "周杰伦", null, null, null, null,
                audio.getId(), null, "{}", now, actor.getId());
        track.publish(actor.getId(), now);
        tracks.save(track);
        bearer = "Bearer " + tokens.issueLegacyWithoutAuthTime(new UserAuthorizationSnapshot(actor.getId(),
                actor.getUsername(), actor.getEmail(), actor.getUserRole(), actor.getTokenVersion()),
                UserRole.USER, LoginType.USERNAME_PASSWORD).tokenValue();
    }

    @Test
    void should_return_503_when_search_content_query_exceeds_mysql_budget() throws Exception {
        assertBudget("CONTENT");
    }

    @Test
    void should_return_503_when_search_count_query_exceeds_mysql_budget() throws Exception {
        assertBudget("COUNT");
    }

    private void assertBudget(String target) throws Exception {
        SlowSearchInspector.TARGET.set(target);
        try {
            var result = mvc.perform(get("/api/v1/tracks").header("Authorization", bearer).param("q", "晴"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("TRACK_STORE_UNAVAILABLE")).andReturn();
            assertInstanceOf(TrackStoreUnavailableException.class, result.getResolvedException());
            Throwable failure = result.getResolvedException();
            while (failure != null && !(failure instanceof SQLException)) {
                failure = failure.getCause();
            }
            var sqlException = assertInstanceOf(SQLException.class, failure);
            assertEquals(3024, sqlException.getErrorCode());
            assertEquals("HY000", sqlException.getSQLState());
            assertTrue(SlowSearchInspector.MODIFIED.get().contains("SLEEP(10)=0"));
        } finally {
            SlowSearchInspector.clear();
        }
        mvc.perform(get("/api/v1/tracks").header("Authorization", bearer).param("q", "晴"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }
}

package org.example.music.favorite;

import java.sql.Statement;
import java.time.Instant;
import org.example.music.security.JwtTokenService;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class FavoriteIntegrationSupport {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4"))
            .withExposedPorts(6379);

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @Autowired PlatformTransactionManager transactions;

    int userId;
    int adminId;
    long publishedId;
    long draftId;
    long archivedId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
    }

    @BeforeEach
    void seed() {
        jdbc.update("delete from media_imports");
        jdbc.update("delete from tracks");
        jdbc.update("delete from media_assets");
        jdbc.update("delete from user_role_history");
        jdbc.update("delete from users");
        userId = createUser("favorite-user", "USER");
        adminId = createUser("favorite-admin", "ADMIN");
        publishedId = createTrack("PUBLISHED");
        draftId = createTrack("DRAFT");
        archivedId = createTrack("ARCHIVED");
    }

    int createUser(String username, String role) {
        return (int) insert("insert into users(username,password_token,user_role) values(?,?,?)",
                username, "test-hash", role);
    }

    long createTrack(String status) {
        long audioId = insert("""
                insert into media_assets(kind,bucket,object_key,content_type,original_filename,
                    size_bytes,sha256,created_at,created_by)
                values('AUDIO','test-audio',?,'audio/mpeg','test.mp3',1,?,UTC_TIMESTAMP(6),?)
                """, java.util.UUID.randomUUID().toString(), "a".repeat(64), adminId);
        return insert("""
                insert into tracks(title,artist_name,audio_asset_id,status,created_at,updated_at,created_by,updated_by)
                values('Favorite track','Artist',?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,?)
                """, audioId, status, adminId, adminId);
    }

    long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    String bearer(int id, UserRole role) {
        var row = jdbc.queryForMap("select username,user_role,token_version from users where id=?", id);
        return "Bearer " + tokens.issue(new UserAuthorizationSnapshot(id, (String) row.get("username"), null,
                        UserRole.valueOf((String) row.get("user_role")), ((Number) row.get("token_version")).longValue()),
                role, LoginType.USERNAME_PASSWORD, Instant.now().minusSeconds(1)).tokenValue();
    }

    TransactionTemplate transaction() {
        return new TransactionTemplate(transactions);
    }

    long count(long trackId) {
        return jdbc.queryForObject("select count(*) from user_favorites where track_id=?", Long.class, trackId);
    }
}

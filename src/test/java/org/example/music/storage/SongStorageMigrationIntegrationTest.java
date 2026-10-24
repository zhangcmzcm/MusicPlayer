package org.example.music.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@EnabledIfDockerAvailable
class SongStorageMigrationIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");

    @Test
    void should_create_song_storage_tables_when_migrations_run() {
        migrate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        ));
        Set<String> tables = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                String.class,
                MYSQL.getDatabaseName()
        ));

        assertTrue(tables.containsAll(Set.of("users", "media_assets", "tracks", "media_imports")));
    }

    @Test
    void should_apply_no_changes_when_schema_is_already_current() {
        migrate();

        int applied = migrate();

        assertEquals(0, applied);
    }

    private int migrate() {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate()
                .migrationsExecuted;
    }
}

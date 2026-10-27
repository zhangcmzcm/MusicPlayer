package org.example.music.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.Optional;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.dto.TrackDraftRequest;
import org.example.music.track.dto.TrackDraftResponse;
import org.example.music.track.exception.TrackVersionConflictException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.track.service.TrackDraftService;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
class TrackDraftIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");

    @Autowired
    private TrackDraftService trackDraftService;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private UserRepository userRepository;

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
        trackRepository.deleteAll();
        mediaAssetRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void should_increment_version_and_reject_stale_version_when_draft_is_updated() {
        TrackEntity track = createTrack();
        TrackDraftRequest first = request(0L);
        first.setTitle("第一次修改");

        TrackDraftResponse updated = trackDraftService.updateDraft(track.getId(), first, editorId());

        assertEquals(1L, updated.version());
        assertEquals("第一次修改", updated.title());

        TrackDraftRequest stale = request(0L);
        stale.setTitle("过期修改");

        assertThrows(
                TrackVersionConflictException.class,
                () -> trackDraftService.updateDraft(track.getId(), stale, editorId())
        );
    }

    private Integer editorId() {
        return userRepository.findByUsername("draft-admin").orElseThrow().getId();
    }

    private TrackEntity createTrack() {
        UserEntity user = userRepository.save(new UserEntity(
                "draft-admin",
                "draft-admin@example.com",
                "password-hash",
                UserRole.ADMIN
        ));
        MediaAssetEntity audio = mediaAssetRepository.save(MediaAssetEntity.create(
                MediaAssetKind.AUDIO,
                "music-audio",
                "audio/draft.mp3",
                "audio/mpeg",
                "draft.mp3",
                10L,
                "a".repeat(64),
                LocalDateTime.now(),
                user.getId()
        ));
        return trackRepository.save(TrackEntity.createDraft(
                "原标题",
                "原艺术家",
                "原专辑",
                1000L,
                1,
                1,
                audio.getId(),
                null,
                "{}",
                LocalDateTime.now(),
                user.getId()
        ));
    }

    private static TrackDraftRequest request(Long version) {
        TrackDraftRequest request = new TrackDraftRequest();
        request.setVersion(version);
        return request;
    }
}

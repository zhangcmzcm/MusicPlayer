package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import java.time.LocalDateTime;
import java.util.Optional;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackCoverNotFoundException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class TrackCoverQueryServiceTest {
    private final TrackRepository tracks = mock(TrackRepository.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final TrackCoverQueryService query = new TrackCoverQueryService(tracks, assets);
    private final AuthenticatedUserPrincipal admin = new AuthenticatedUserPrincipal(7, UserRole.ADMIN);
    private final AuthenticatedUserPrincipal owner = new AuthenticatedUserPrincipal(1, UserRole.USER);

    private TrackEntity track(TrackStatus status, Long cover) {
        var track = TrackEntity.createDraft("title", "artist", null, null, null, null, 2L, cover,
                "{}", LocalDateTime.now(), 1);
        ReflectionTestUtils.setField(track, "status", status);
        when(tracks.findById(42L)).thenReturn(Optional.of(track));
        return track;
    }

    private MediaAssetEntity asset() {
        return MediaAssetEntity.create(MediaAssetKind.COVER, "private", "cover.jpg", "image/jpeg", "c.jpg",
                10L, "A".repeat(64), LocalDateTime.now(), 1);
    }

    @ParameterizedTest
    @EnumSource(TrackStatus.class)
    void should_apply_role_and_status_visibility(TrackStatus status) {
        track(status, 3L);
        when(assets.findById(3L)).thenReturn(Optional.of(asset()));
        if (status != TrackStatus.PUBLISHED) {
            assertThrows(TrackNotFoundException.class, () -> query.load(42L, owner));
            verifyNoInteractions(assets);
        } else {
            assertEquals("a".repeat(64), query.load(42L, owner).sha256());
        }
        assertEquals(42L, query.load(42L, admin).trackId());
    }

    @Test
    void should_return_not_found_for_absent_track() {
        assertThrows(TrackNotFoundException.class, () -> query.load(42L, admin));
        verifyNoInteractions(assets);
    }

    @Test
    void should_return_cover_not_found_for_empty_reference() {
        track(TrackStatus.PUBLISHED, null);
        assertThrows(TrackCoverNotFoundException.class, () -> query.load(42L, owner));
        verifyNoInteractions(assets);
    }

    @Test
    void should_fail_for_dangling_reference() {
        track(TrackStatus.PUBLISHED, 3L);
        assertThrows(IllegalStateException.class, () -> query.load(42L, owner));
    }

    @ParameterizedTest
    @ValueSource(strings = {"kind", "contentType", "sizeBytes", "sha256", "bucket", "objectKey"})
    void should_fail_for_invalid_local_metadata(String field) {
        track(TrackStatus.PUBLISHED, 3L);
        var asset = asset();
        Object invalid = switch (field) {
            case "kind" -> MediaAssetKind.AUDIO;
            case "sizeBytes" -> 0L;
            case "sha256" -> "not-a-digest";
            default -> "";
        };
        ReflectionTestUtils.setField(asset, field, invalid);
        when(assets.findById(3L)).thenReturn(Optional.of(asset));
        assertThrows(IllegalStateException.class, () -> query.load(42L, owner));
    }
}

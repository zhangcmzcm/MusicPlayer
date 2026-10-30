package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.example.music.track.dto.TrackDetailResponse;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class TrackDetailServiceTest {
    private final TrackRepository tracks = mock(TrackRepository.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final TrackDetailService service = new TrackDetailService(tracks, assets);
    private final AuthenticatedUserPrincipal admin = new AuthenticatedUserPrincipal(1, UserRole.ADMIN);
    private final AuthenticatedUserPrincipal user = new AuthenticatedUserPrincipal(1, UserRole.USER);

    private TrackEntity track(TrackStatus status, Long cover) {
        TrackEntity track = TrackEntity.createDraft("标题", "艺术家", null, null, null, null,
                2L, cover, "{}", LocalDateTime.now(), 1);
        ReflectionTestUtils.setField(track, "id", 42L);
        ReflectionTestUtils.setField(track, "status", status);
        when(tracks.findById(42L)).thenReturn(Optional.of(track));
        return track;
    }

    @ParameterizedTest
    @EnumSource(TrackStatus.class)
    void should_allow_admin_for_every_status(TrackStatus status) {
        track(status, null);
        TrackDetailResponse response = service.getDetail(42L, admin);
        assertEquals(status.name(), response.status());
        assertEquals("标题", response.title());
        assertEquals("艺术家", response.artistName());
        assertNull(response.albumName());
        assertNull(response.durationMs());
        assertNull(response.trackNumber());
        assertNull(response.discNumber());
        assertNull(response.cover());
        verifyNoInteractions(assets);
    }

    @ParameterizedTest
    @EnumSource(TrackStatus.class)
    void should_enforce_user_visibility_including_owner(TrackStatus status) {
        track(status, 3L);
        if (status == TrackStatus.PUBLISHED) {
            when(assets.findById(3L)).thenReturn(Optional.of(MediaAssetEntity.create(MediaAssetKind.COVER,
                    "private", "secret", "image/jpeg", "cover.jpg", 55L, "a".repeat(64), LocalDateTime.now(), 1)));
            var cover = service.getDetail(42L, user).cover();
            assertEquals("/api/v1/tracks/42/cover", cover.url());
            assertEquals("image/jpeg", cover.contentType());
            assertEquals(55L, cover.sizeBytes());
        } else {
            assertThrows(TrackNotFoundException.class, () -> service.getDetail(42L, user));
            verifyNoInteractions(assets);
        }
    }

    @Test
    void should_preserve_maximum_text_lengths_and_zero_duration() {
        TrackEntity track = track(TrackStatus.PUBLISHED, null);
        ReflectionTestUtils.setField(track, "title", "题".repeat(255));
        ReflectionTestUtils.setField(track, "artistName", "艺".repeat(255));
        ReflectionTestUtils.setField(track, "albumName", "专".repeat(255));
        ReflectionTestUtils.setField(track, "durationMs", 0L);
        ReflectionTestUtils.setField(track, "trackNumber", 1);
        ReflectionTestUtils.setField(track, "discNumber", 1);
        var response = service.getDetail(42L, user);
        assertEquals("题".repeat(255), response.title());
        assertEquals("艺".repeat(255), response.artistName());
        assertEquals("专".repeat(255), response.albumName());
        assertEquals(0L, response.durationMs());
        assertEquals(1, response.trackNumber());
        assertEquals(1, response.discNumber());
    }

    @Test
    void should_return_not_found_when_absent() {
        when(tracks.findById(42L)).thenReturn(Optional.empty());
        assertThrows(TrackNotFoundException.class, () -> service.getDetail(42L, admin));
        verifyNoInteractions(assets);
    }

    @Test
    void should_fail_when_cover_reference_is_missing() {
        track(TrackStatus.PUBLISHED, 3L);
        when(assets.findById(3L)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.getDetail(42L, user));
    }

    @Test
    void should_fail_when_cover_kind_is_audio() {
        track(TrackStatus.PUBLISHED, 3L);
        when(assets.findById(3L)).thenReturn(Optional.of(MediaAssetEntity.create(MediaAssetKind.AUDIO,
                "private", "secret", "audio/mpeg", "audio.mp3", 55L, "a".repeat(64), LocalDateTime.now(), 1)));
        assertThrows(IllegalStateException.class, () -> service.getDetail(42L, user));
    }

    @Test
    void should_translate_track_store_failure() {
        when(tracks.findById(42L)).thenThrow(new DataAccessResourceFailureException("database"));
        assertThrows(TrackStoreUnavailableException.class, () -> service.getDetail(42L, user));
    }

    @Test
    void should_translate_cover_store_failure() {
        track(TrackStatus.PUBLISHED, 3L);
        when(assets.findById(3L)).thenThrow(new DataAccessResourceFailureException("database"));
        assertThrows(TrackStoreUnavailableException.class, () -> service.getDetail(42L, user));
    }
}

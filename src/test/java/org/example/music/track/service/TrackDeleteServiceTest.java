package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.storage.exception.AudioStorageUnavailableException;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackDeleteFailedException;
import org.example.music.track.exception.TrackMediaCleanupFailedException;
import org.example.music.track.exception.TrackNotArchivedException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class TrackDeleteServiceTest {

    private TrackRepository trackRepository;
    private MediaAssetRepository mediaAssetRepository;
    private AudioStorage audioStorage;
    private TrackDeleteTransactionService transactionService;
    private TrackDeleteService service;

    @BeforeEach
    void setUp() {
        trackRepository = mock(TrackRepository.class);
        mediaAssetRepository = mock(MediaAssetRepository.class);
        audioStorage = mock(AudioStorage.class);
        transactionService = mock(TrackDeleteTransactionService.class);
        service = new TrackDeleteService(
                trackRepository,
                mediaAssetRepository,
                audioStorage,
                transactionService
        );
    }

    @Test
    void should_delete_archived_track_and_media_objects() {
        TrackEntity track = track(TrackStatus.ARCHIVED);
        MediaAssetEntity audio = asset(51L, MediaAssetKind.AUDIO, "audio-bucket", "audio.mp3");
        MediaAssetEntity cover = asset(52L, MediaAssetKind.COVER, "cover-bucket", "cover.jpg");
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));
        when(mediaAssetRepository.findAllById(Set.of(51L, 52L))).thenReturn(List.of(audio, cover));
        doNothing().when(audioStorage).delete("audio-bucket", "audio.mp3");
        doNothing().when(audioStorage).delete("cover-bucket", "cover.jpg");

        service.delete(42L);

        verify(audioStorage).delete("audio-bucket", "audio.mp3");
        verify(audioStorage).delete("cover-bucket", "cover.jpg");
        verify(transactionService).delete(42L, Set.of(51L, 52L));
    }

    @Test
    void should_reject_delete_when_track_is_draft() {
        TrackEntity track = track(TrackStatus.DRAFT);
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));

        assertThrows(TrackNotArchivedException.class, () -> service.delete(42L));
    }

    @Test
    void should_reject_delete_when_track_is_published() {
        TrackEntity track = track(TrackStatus.PUBLISHED);
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));

        assertThrows(TrackNotArchivedException.class, () -> service.delete(42L));
    }

    @Test
    void should_throw_not_found_when_track_does_not_exist() {
        when(trackRepository.findById(42L)).thenReturn(Optional.empty());

        assertThrows(TrackNotFoundException.class, () -> service.delete(42L));
    }

    @Test
    void should_attempt_all_objects_and_fail_when_one_object_delete_fails() {
        TrackEntity track = track(TrackStatus.ARCHIVED);
        MediaAssetEntity audio = asset(51L, MediaAssetKind.AUDIO, "audio-bucket", "audio.mp3");
        MediaAssetEntity cover = asset(52L, MediaAssetKind.COVER, "cover-bucket", "cover.jpg");
        AudioStorageUnavailableException failure = new AudioStorageUnavailableException("down", new RuntimeException());
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));
        when(mediaAssetRepository.findAllById(Set.of(51L, 52L))).thenReturn(List.of(audio, cover));
        doThrow(failure).when(audioStorage).delete("audio-bucket", "audio.mp3");
        doNothing().when(audioStorage).delete("cover-bucket", "cover.jpg");

        TrackMediaCleanupFailedException thrown =
                assertThrows(TrackMediaCleanupFailedException.class, () -> service.delete(42L));

        assertSame(failure, thrown.getCause());
        verify(audioStorage).delete("audio-bucket", "audio.mp3");
        verify(audioStorage).delete("cover-bucket", "cover.jpg");
    }

    @Test
    void should_map_read_failure_to_store_unavailable() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");
        when(trackRepository.findById(42L)).thenThrow(failure);

        TrackStoreUnavailableException thrown =
                assertThrows(TrackStoreUnavailableException.class, () -> service.delete(42L));

        assertSame(failure, thrown.getCause());
    }

    @Test
    void should_map_missing_media_asset_metadata_to_delete_failure() {
        TrackEntity track = track(TrackStatus.ARCHIVED);
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));
        when(mediaAssetRepository.findAllById(Set.of(51L))).thenReturn(List.of());

        assertThrows(TrackDeleteFailedException.class, () -> service.delete(42L));
    }

    @Test
    void should_propagate_database_delete_failure() {
        TrackEntity track = track(TrackStatus.ARCHIVED);
        ReflectionTestUtils.setField(track, "coverAssetId", null);
        MediaAssetEntity audio = asset(51L, MediaAssetKind.AUDIO, "audio-bucket", "audio.mp3");
        TrackDeleteFailedException failure = new TrackDeleteFailedException(42L, new RuntimeException());
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));
        when(mediaAssetRepository.findAllById(Set.of(51L))).thenReturn(List.of(audio));
        doNothing().when(audioStorage).delete("audio-bucket", "audio.mp3");
        doThrow(failure).when(transactionService).delete(42L, Set.of(51L));

        TrackDeleteFailedException thrown =
                assertThrows(TrackDeleteFailedException.class, () -> service.delete(42L));

        assertSame(failure, thrown);
    }

    private static TrackEntity track(TrackStatus status) {
        TrackEntity track = TrackEntity.createDraft(
                "Title",
                "Artist",
                null,
                1000L,
                1,
                1,
                51L,
                52L,
                "{}",
                java.time.LocalDateTime.of(2026, 10, 29, 10, 0),
                7
        );
        ReflectionTestUtils.setField(track, "id", 42L);
        ReflectionTestUtils.setField(track, "version", 3L);
        ReflectionTestUtils.setField(track, "status", status);
        return track;
    }

    private static MediaAssetEntity asset(Long id, MediaAssetKind kind, String bucket, String objectKey) {
        MediaAssetEntity asset = MediaAssetEntity.create(
                kind,
                bucket,
                objectKey,
                kind == MediaAssetKind.AUDIO ? "audio/mpeg" : "image/jpeg",
                objectKey,
                10L,
                "a".repeat(64),
                java.time.LocalDateTime.of(2026, 10, 29, 10, 0),
                7
        );
        ReflectionTestUtils.setField(asset, "id", id);
        return asset;
    }
}
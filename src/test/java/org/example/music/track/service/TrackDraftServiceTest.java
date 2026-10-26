package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.dto.TrackDraftRequest;
import org.example.music.track.dto.TrackDraftResponse;
import org.example.music.track.exception.TrackDraftValidationException;
import org.example.music.track.exception.TrackNotDraftException;
import org.example.music.track.exception.TrackVersionConflictException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TrackDraftServiceTest {

    private TrackRepository trackRepository;
    private MediaAssetRepository mediaAssetRepository;
    private TrackDraftTransactionService transactionService;
    private TrackDraftService service;

    @BeforeEach
    void setUp() {
        trackRepository = mock(TrackRepository.class);
        mediaAssetRepository = mock(MediaAssetRepository.class);
        transactionService = mock(TrackDraftTransactionService.class);
        service = new TrackDraftService(trackRepository, mediaAssetRepository, transactionService);
    }

    @Test
    void should_preserve_omitted_fields_when_request_is_partial() {
        TrackEntity track = track(TrackStatus.DRAFT, 3L);
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));
        when(transactionService.updateDraft(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    TrackEntity updatedTrack = invocation.getArgument(0);
                    updatedTrack.updateDraft(
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            invocation.getArgument(4),
                            invocation.getArgument(5),
                            LocalDateTime.now(),
                            invocation.getArgument(6)
                    );
                    return updatedTrack;
                });
        when(mediaAssetRepository.findById(51L)).thenReturn(Optional.of(audioAsset()));

        TrackDraftRequest request = request(3L);
        request.setTitle("新标题");

        TrackDraftResponse response = service.updateDraft(42L, request, 7);

        assertEquals("新标题", response.title());
        assertEquals("原艺术家", response.artistName());
        assertEquals("原专辑", response.albumName());
        assertEquals(1, response.trackNumber());
        assertEquals(2, response.discNumber());
    }

    @Test
    void should_clear_nullable_fields_when_explicit_null_is_provided() {
        TrackEntity track = track(TrackStatus.DRAFT, 3L);
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));
        when(transactionService.updateDraft(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    TrackEntity updatedTrack = invocation.getArgument(0);
                    updatedTrack.updateDraft(
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            invocation.getArgument(4),
                            invocation.getArgument(5),
                            LocalDateTime.now(),
                            invocation.getArgument(6)
                    );
                    return updatedTrack;
                });
        when(mediaAssetRepository.findById(51L)).thenReturn(Optional.of(audioAsset()));

        TrackDraftRequest request = request(3L);
        request.setAlbumName(null);
        request.setTrackNumber(null);
        request.setDiscNumber(null);

        TrackDraftResponse response = service.updateDraft(42L, request, 7);

        assertNull(response.albumName());
        assertNull(response.trackNumber());
        assertNull(response.discNumber());
    }

    @Test
    void should_reject_blank_title_when_title_is_present() {
        TrackEntity track = track(TrackStatus.DRAFT, 3L);
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));
        TrackDraftRequest request = request(3L);
        request.setTitle("   ");

        assertThrows(TrackDraftValidationException.class, () -> service.updateDraft(42L, request, 7));
    }

    @Test
    void should_reject_stale_version_before_updating() {
        TrackEntity track = track(TrackStatus.DRAFT, 4L);
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));
        TrackDraftRequest request = request(3L);

        assertThrows(TrackVersionConflictException.class, () -> service.updateDraft(42L, request, 7));
    }

    @Test
    void should_reject_non_draft_track() {
        TrackEntity track = track(TrackStatus.PUBLISHED, 3L);
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));
        TrackDraftRequest request = request(3L);

        assertThrows(TrackNotDraftException.class, () -> service.updateDraft(42L, request, 7));
    }

    private static TrackDraftRequest request(Long version) {
        TrackDraftRequest request = new TrackDraftRequest();
        request.setVersion(version);
        return request;
    }

    private static TrackEntity track(TrackStatus status, Long version) {
        TrackEntity track = TrackEntity.createDraft(
                "原标题",
                "原艺术家",
                "原专辑",
                1000L,
                1,
                2,
                51L,
                null,
                "{}",
                LocalDateTime.now(),
                7
        );
        ReflectionTestUtils.setField(track, "version", version);
        ReflectionTestUtils.setField(track, "status", status);
        return track;
    }

    private static MediaAssetEntity audioAsset() {
        return MediaAssetEntity.create(
                MediaAssetKind.AUDIO,
                "music-audio",
                "audio/key.mp3",
                "audio/mpeg",
                "song.mp3",
                10L,
                "a".repeat(64),
                LocalDateTime.now(),
                7
        );
    }
}

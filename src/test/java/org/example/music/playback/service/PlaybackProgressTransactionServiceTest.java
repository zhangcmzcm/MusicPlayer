package org.example.music.playback.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;
import org.example.music.playback.dto.SavePlaybackProgressRequest;
import org.example.music.playback.exception.PlaybackProgressSequenceConflictException;
import org.example.music.playback.exception.PlaybackProgressStaleSequenceException;
import org.example.music.playback.exception.PlaybackProgressValidationException;
import org.example.music.playback.repository.PlaybackProgressRepository;
import org.example.music.playback.repository.PlaybackProgressRow;
import org.example.music.playback.repository.PlaybackProgressTrackRow;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.EnumSource;

class PlaybackProgressTransactionServiceTest {
    private static final String DEVICE = "bcf04f9c-3876-4a77-8f6d-632ebd78db2e";
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00.123456789Z");
    private final PlaybackProgressRepository repository = mock(PlaybackProgressRepository.class);
    private final AuthenticatedUserPrincipal user = new AuthenticatedUserPrincipal(7, UserRole.USER);
    private final PlaybackProgressTransactionService service = new PlaybackProgressTransactionService(
            repository, Clock.fixed(NOW, ZoneOffset.UTC));
    private final PlaybackProgressRow saved = new PlaybackProgressRow(DEVICE, 123L, 83, 42,
            Instant.parse("2026-11-14T00:00:00Z"));

    @BeforeEach
    void setup() {
        when(repository.lockTrackForShare(anyLong())).thenReturn(Optional.of(
                new PlaybackProgressTrackRow(TrackStatus.PUBLISHED, 215430L)));
        when(repository.lockProgress(7, DEVICE)).thenReturn(saved);
        when(repository.updateIfSequenceOlder(eq(7), eq(DEVICE), anyLong(), anyLong(), anyLong(), any())).thenReturn(1);
    }

    @Test
    void should_preserve_saved_event_for_identical_sequence() {
        var result = service.save(DEVICE, new SavePlaybackProgressRequest(123L, 83L, 42L), user);
        assertEquals(saved.updatedAt(), result.updatedAt());
        assertEquals(42, result.sequence());
        verify(repository, never()).updateIfSequenceOlder(anyInt(), anyString(), anyLong(), anyLong(), anyLong(), any());
        var order = inOrder(repository);
        order.verify(repository).lockTrackForShare(123L);
        order.verify(repository).insertIfAbsent(eq(7), eq(DEVICE), eq(123L), eq(83L), eq(42L), any());
        order.verify(repository).lockProgress(7, DEVICE);
    }

    @Test
    void should_reject_stale_and_conflicting_sequence() {
        assertEquals(42, assertThrows(PlaybackProgressStaleSequenceException.class,
                () -> service.save(DEVICE, new SavePlaybackProgressRequest(123L, 83L, 41L), user)).getCurrentSequence());
        assertEquals(42, assertThrows(PlaybackProgressSequenceConflictException.class,
                () -> service.save(DEVICE, new SavePlaybackProgressRequest(123L, 84L, 42L), user)).getCurrentSequence());
        assertThrows(PlaybackProgressSequenceConflictException.class,
                () -> service.save(DEVICE, new SavePlaybackProgressRequest(124L, 83L, 42L), user));
        verify(repository, never()).updateIfSequenceOlder(anyInt(), anyString(), anyLong(), anyLong(), anyLong(), any());
    }

    @ParameterizedTest
    @MethodSource("newPositions")
    void should_allow_backward_seek_and_end_reset_with_newer_sequence(long position) {
        var newer = new PlaybackProgressRow(DEVICE, 123L, position, 43, NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        when(repository.lockProgress(7, DEVICE)).thenReturn(saved, newer);
        var result = service.save(DEVICE, new SavePlaybackProgressRequest(123L, position, 43L), user);
        assertEquals(position, result.positionSeconds());
        assertEquals(43, result.sequence());
        verify(repository).updateIfSequenceOlder(7, DEVICE, 123L, position, 43,
                java.time.LocalDateTime.ofInstant(newer.updatedAt(), ZoneOffset.UTC));
    }

    static Stream<Long> newPositions() {
        return Stream.of(30L, 0L, 215L);
    }

    @ParameterizedTest
    @EnumSource(value = TrackStatus.class, names = {"DRAFT", "ARCHIVED"})
    void should_hide_unpublished_tracks_from_user_role(TrackStatus status) {
        when(repository.lockTrackForShare(123L)).thenReturn(Optional.of(new PlaybackProgressTrackRow(status, null)));
        assertThrows(TrackNotFoundException.class,
                () -> service.save(DEVICE, new SavePlaybackProgressRequest(123L, 83L, 42L), user));
        verify(repository, never()).lockProgress(anyInt(), anyString());
    }

    @ParameterizedTest
    @EnumSource(TrackStatus.class)
    void should_allow_all_existing_states_for_admin_login(TrackStatus status) {
        when(repository.lockTrackForShare(123L)).thenReturn(Optional.of(new PlaybackProgressTrackRow(status, null)));
        when(repository.lockProgress(8, DEVICE)).thenReturn(saved);
        assertEquals(42, service.save(DEVICE, new SavePlaybackProgressRequest(123L, 83L, 42L),
                new AuthenticatedUserPrincipal(8, UserRole.ADMIN)).sequence());
    }

    static Stream<Arguments> durationCases() {
        return Stream.of(Arguments.of(null, Long.MAX_VALUE, true), Arguments.of(0L, Long.MAX_VALUE, true),
                Arguments.of(900L, 0L, true), Arguments.of(900L, 1L, false),
                Arguments.of(1000L, 1L, true), Arguments.of(1000L, 2L, false),
                Arguments.of(215430L, 215L, true), Arguments.of(215430L, 216L, false),
                Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE, false));
    }

    @ParameterizedTest
    @MethodSource("durationCases")
    void should_enforce_known_duration_without_overflow(Long duration, long position, boolean allowed) {
        when(repository.lockTrackForShare(123L)).thenReturn(Optional.of(new PlaybackProgressTrackRow(TrackStatus.PUBLISHED, duration)));
        when(repository.lockProgress(7, DEVICE)).thenReturn(new PlaybackProgressRow(DEVICE, 123L, position, 42, saved.updatedAt()));
        var input = new SavePlaybackProgressRequest(123L, position, 42L);
        if (allowed) {
            assertEquals(position, service.save(DEVICE, input, user).positionSeconds());
        } else {
            assertThrows(PlaybackProgressValidationException.class, () -> service.save(DEVICE, input, user));
            verify(repository, never()).lockProgress(anyInt(), anyString());
        }
    }

    @Test
    void should_reject_missing_track_before_progress_access() {
        when(repository.lockTrackForShare(123L)).thenReturn(Optional.empty());
        assertThrows(TrackNotFoundException.class,
                () -> service.save(DEVICE, new SavePlaybackProgressRequest(123L, 83L, 42L), user));
        verify(repository, never()).lockProgress(anyInt(), anyString());
    }

    @Test
    void should_preserve_sequence_after_track_association_is_cleared() {
        when(repository.lockProgress(7, DEVICE)).thenReturn(new PlaybackProgressRow(DEVICE, null, 83, 42, saved.updatedAt()));
        assertThrows(PlaybackProgressStaleSequenceException.class,
                () -> service.save(DEVICE, new SavePlaybackProgressRequest(124L, 0L, 41L), user));
        assertThrows(PlaybackProgressSequenceConflictException.class,
                () -> service.save(DEVICE, new SavePlaybackProgressRequest(124L, 0L, 42L), user));
    }
}

package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.dto.TrackPublishResponse;
import org.example.music.track.exception.TrackNotDraftException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.track.repository.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class TrackPublishServiceTest {

    private TrackRepository trackRepository;
    private TrackPublishTransactionService transactionService;
    private TrackPublishService service;

    @BeforeEach
    void setUp() {
        trackRepository = mock(TrackRepository.class);
        transactionService = mock(TrackPublishTransactionService.class);
        service = new TrackPublishService(trackRepository, transactionService);
    }

    @Test
    void should_publish_track_and_map_response() {
        TrackEntity track = track(TrackStatus.DRAFT);
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));
        when(transactionService.publish(track, 7)).thenAnswer(invocation -> {
            track.publish(7, LocalDateTime.of(2026, 10, 27, 11, 0));
            return track;
        });

        TrackPublishResponse response = service.publish(42L, 7);

        assertEquals(42L, response.id());
        assertEquals("PUBLISHED", response.status());
        assertEquals(3L, response.version());
        assertEquals(java.time.Instant.parse("2026-10-27T11:00:00Z"), response.updatedAt());
    }

    @Test
    void should_throw_not_found_when_track_does_not_exist() {
        when(trackRepository.findById(42L)).thenReturn(Optional.empty());

        assertThrows(TrackNotFoundException.class, () -> service.publish(42L, 7));
    }

    @Test
    void should_throw_not_draft_when_track_is_already_published() {
        TrackEntity track = track(TrackStatus.PUBLISHED);
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));

        assertThrows(TrackNotDraftException.class, () -> service.publish(42L, 7));
    }

    @Test
    void should_propagate_optimistic_locking_failure() {
        TrackEntity track = track(TrackStatus.DRAFT);
        ObjectOptimisticLockingFailureException conflict =
                new ObjectOptimisticLockingFailureException(TrackEntity.class, 42L);
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));
        when(transactionService.publish(track, 7)).thenThrow(conflict);

        ObjectOptimisticLockingFailureException thrown =
                assertThrows(ObjectOptimisticLockingFailureException.class, () -> service.publish(42L, 7));

        assertSame(conflict, thrown);
    }

    @Test
    void should_map_other_data_access_failure_to_store_unavailable() {
        TrackEntity track = track(TrackStatus.DRAFT);
        DataAccessResourceFailureException databaseFailure = new DataAccessResourceFailureException("db down");
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));
        when(transactionService.publish(track, 7)).thenThrow(databaseFailure);

        TrackStoreUnavailableException thrown =
                assertThrows(TrackStoreUnavailableException.class, () -> service.publish(42L, 7));

        assertSame(databaseFailure, thrown.getCause());
    }

    @Test
    void should_map_read_failure_to_store_unavailable() {
        DataAccessResourceFailureException databaseFailure = new DataAccessResourceFailureException("db down");
        when(trackRepository.findById(42L)).thenThrow(databaseFailure);

        TrackStoreUnavailableException thrown =
                assertThrows(TrackStoreUnavailableException.class, () -> service.publish(42L, 7));

        assertSame(databaseFailure, thrown.getCause());
    }

    private static TrackEntity track(TrackStatus status) {
        TrackEntity track = TrackEntity.createDraft(
                "Title",
                "Artist",
                null,
                1000L,
                1,
                1,
                10L,
                null,
                "{}",
                LocalDateTime.of(2026, 10, 27, 10, 0),
                7
        );
        ReflectionTestUtils.setField(track, "id", 42L);
        ReflectionTestUtils.setField(track, "version", 3L);
        ReflectionTestUtils.setField(track, "status", status);
        return track;
    }
}

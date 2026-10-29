package org.example.music.track.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import org.example.music.track.exception.TrackAlreadyArchivedException;
import org.example.music.track.exception.TrackNotDraftException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TrackEntityTest {

    @Test
    void should_publish_draft_and_update_audit_fields() {
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
        LocalDateTime publishedAt = LocalDateTime.of(2026, 10, 27, 11, 0);

        track.publish(9, publishedAt);

        assertEquals(TrackStatus.PUBLISHED, track.getStatus());
        assertEquals(publishedAt, track.getUpdatedAt());
        assertEquals(9, track.getUpdatedBy());
    }

    @Test
    void should_reject_publish_when_track_is_not_draft_without_mutating_audit_fields() {
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
        LocalDateTime originalUpdatedAt = track.getUpdatedAt();
        ReflectionTestUtils.setField(track, "status", TrackStatus.PUBLISHED);

        assertThrows(TrackNotDraftException.class, () -> track.publish(9, LocalDateTime.of(2026, 10, 27, 11, 0)));

        assertEquals(TrackStatus.PUBLISHED, track.getStatus());
        assertEquals(originalUpdatedAt, track.getUpdatedAt());
        assertEquals(7, track.getUpdatedBy());
    }

    @Test
    void should_reject_publish_when_track_is_archived_without_mutating_audit_fields() {
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
        LocalDateTime originalUpdatedAt = track.getUpdatedAt();
        ReflectionTestUtils.setField(track, "status", TrackStatus.ARCHIVED);

        assertThrows(TrackNotDraftException.class, () -> track.publish(9, LocalDateTime.of(2026, 10, 27, 11, 0)));

        assertEquals(TrackStatus.ARCHIVED, track.getStatus());
        assertEquals(originalUpdatedAt, track.getUpdatedAt());
        assertEquals(7, track.getUpdatedBy());
    }

    @Test
    void should_archive_draft_and_update_audit_fields() {
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
                LocalDateTime.of(2026, 10, 29, 10, 0),
                7
        );
        LocalDateTime archivedAt = LocalDateTime.of(2026, 10, 29, 11, 0);

        track.archive(9, archivedAt);

        assertEquals(TrackStatus.ARCHIVED, track.getStatus());
        assertEquals(archivedAt, track.getUpdatedAt());
        assertEquals(9, track.getUpdatedBy());
    }

    @Test
    void should_archive_published_track_and_update_audit_fields() {
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
                LocalDateTime.of(2026, 10, 29, 10, 0),
                7
        );
        ReflectionTestUtils.setField(track, "status", TrackStatus.PUBLISHED);
        LocalDateTime archivedAt = LocalDateTime.of(2026, 10, 29, 11, 0);

        track.archive(9, archivedAt);

        assertEquals(TrackStatus.ARCHIVED, track.getStatus());
        assertEquals(archivedAt, track.getUpdatedAt());
        assertEquals(9, track.getUpdatedBy());
    }

    @Test
    void should_reject_archive_when_track_is_already_archived_without_mutating_audit_fields() {
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
                LocalDateTime.of(2026, 10, 29, 10, 0),
                7
        );
        LocalDateTime originalUpdatedAt = track.getUpdatedAt();
        ReflectionTestUtils.setField(track, "status", TrackStatus.ARCHIVED);

        assertThrows(TrackAlreadyArchivedException.class, () -> track.archive(9, LocalDateTime.of(2026, 10, 29, 11, 0)));

        assertEquals(TrackStatus.ARCHIVED, track.getStatus());
        assertEquals(originalUpdatedAt, track.getUpdatedAt());
        assertEquals(7, track.getUpdatedBy());
    }
}

package org.example.music.playback;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TimeZone;
import org.example.music.playback.dto.SavePlaybackProgressRequest;
import org.example.music.playback.exception.PlaybackProgressSequenceConflictException;
import org.example.music.playback.exception.PlaybackProgressStaleSequenceException;
import org.example.music.playback.repository.PlaybackProgressRepository;
import org.example.music.playback.service.PlaybackProgressTransactionService;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

class PlaybackProgressRepositoryIntegrationTest extends PlaybackProgressIntegrationSupport {
    @Autowired PlaybackProgressRepository repository;
    @Autowired PlaybackProgressTransactionService service;

    @Test
    void should_apply_migration_primary_key_foreign_keys_and_checks() {
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version='11' and success=1", Integer.class));
        assertEquals(List.of("user_id", "device_id"), jdbc.queryForList("""
                select column_name from information_schema.key_column_usage
                where table_schema=database() and table_name='user_playback_progress' and constraint_name='PRIMARY'
                order by ordinal_position
                """, String.class));
        var rules = jdbc.queryForList("""
                select delete_rule from information_schema.referential_constraints
                where constraint_schema=database() and table_name='user_playback_progress' order by delete_rule
                """, String.class);
        assertEquals(List.of("CASCADE", "SET NULL"), rules);
        assertEquals(2, jdbc.queryForObject("""
                select count(*) from information_schema.table_constraints
                where constraint_schema=database() and table_name='user_playback_progress' and constraint_type='CHECK'
                """, Integer.class));
        assertEquals("YES", jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_schema=database() and table_name='user_playback_progress' and column_name='track_id'
                """, String.class));
        assertEquals("ascii_bin", jdbc.queryForObject("""
                select collation_name from information_schema.columns
                where table_schema=database() and table_name='user_playback_progress' and column_name='device_id'
                """, String.class));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from information_schema.statistics
                where table_schema=database() and table_name='user_playback_progress'
                    and index_name='ix_user_playback_progress_track' and column_name='track_id'
                """, Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Pacific/Honolulu", "Asia/Shanghai"})
    void should_round_trip_utc_microseconds_independent_of_jvm_timezone(String zone) {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            var time = LocalDateTime.of(2026, 11, 15, 0, 0, 0, 123456000);
            var row = transaction().execute(status -> {
                repository.lockTrackForShare(publishedId).orElseThrow();
                repository.insertIfAbsent(userId, DEVICE, publishedId, 83, 42, time);
                return repository.lockProgress(userId, DEVICE);
            });
            assertNotNull(row);
            assertEquals(Instant.parse("2026-11-15T00:00:00.123456Z"), row.updatedAt());
            var original = progress(userId, DEVICE);
            transaction().executeWithoutResult(status ->
                    repository.insertIfAbsent(userId, DEVICE, publishedId, 100, 43, time.plusDays(1)));
            assertEquals(original, progress(userId, DEVICE));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void should_preserve_full_audit_row_for_identical_event() {
        var actor = new AuthenticatedUserPrincipal(userId, UserRole.USER);
        var input = new SavePlaybackProgressRequest(publishedId, 83L, 42L);
        var first = service.save(DEVICE, input, actor);
        var original = progress(userId, DEVICE);
        assertEquals(userId, original.get("created_by"));
        assertEquals(userId, original.get("updated_by"));
        assertEquals(first, service.save(DEVICE, input, actor));
        assertEquals(original, progress(userId, DEVICE));
        var updated = service.save(DEVICE, new SavePlaybackProgressRequest(publishedId, 0L, 43L), actor);
        var newRow = progress(userId, DEVICE);
        assertEquals(original.get("created_at"), newRow.get("created_at"));
        assertEquals(original.get("created_by"), newRow.get("created_by"));
        assertEquals(0, updated.positionSeconds());
        assertEquals(43, updated.sequence());
    }

    @Test
    void should_preserve_sequence_after_track_delete_and_allow_newer_visible_track() {
        var actor = new AuthenticatedUserPrincipal(adminId, UserRole.ADMIN);
        var input = new SavePlaybackProgressRequest(archivedId, 83L, 42L);
        service.save(DEVICE, input, actor);
        var original = progress(adminId, DEVICE);
        jdbc.update("delete from tracks where id=?", archivedId);
        var deleted = progress(adminId, DEVICE);
        assertNull(deleted.get("track_id"));
        for (String field : List.of("last_sequence", "position_seconds", "created_at", "updated_at", "created_by", "updated_by")) {
            assertEquals(original.get(field), deleted.get(field));
        }
        assertThrows(TrackNotFoundException.class, () -> service.save(DEVICE, input, actor));
        assertThrows(PlaybackProgressStaleSequenceException.class, () ->
                service.save(DEVICE, new SavePlaybackProgressRequest(publishedId, 0L, 41L), actor));
        assertThrows(PlaybackProgressSequenceConflictException.class, () ->
                service.save(DEVICE, new SavePlaybackProgressRequest(publishedId, 0L, 42L), actor));
        assertEquals(deleted, progress(adminId, DEVICE));
        assertEquals(43, service.save(DEVICE,
                new SavePlaybackProgressRequest(publishedId, 0L, 43L), actor).sequence());
    }

    @Test
    void should_cascade_rows_when_user_is_deleted() {
        var actor = new AuthenticatedUserPrincipal(userId, UserRole.USER);
        service.save(DEVICE, new SavePlaybackProgressRequest(publishedId, 83L, 42L), actor);
        jdbc.update("delete from users where id=?", userId);
        assertEquals(0, jdbc.queryForObject("select count(*) from user_playback_progress where user_id=?", Integer.class, userId));
    }

    @Test
    void should_restore_state_when_transaction_rolls_back() {
        var actor = new AuthenticatedUserPrincipal(userId, UserRole.USER);
        service.save(DEVICE, new SavePlaybackProgressRequest(publishedId, 83L, 42L), actor);
        var original = progress(userId, DEVICE);
        assertThrows(IllegalStateException.class, () -> transaction().executeWithoutResult(status -> {
            service.save(DEVICE, new SavePlaybackProgressRequest(publishedId, 0L, 43L), actor);
            throw new IllegalStateException("force rollback");
        }));
        assertEquals(original, progress(userId, DEVICE));
    }

    @Test
    void should_enforce_numeric_database_checks() {
        for (long[] invalid : List.of(new long[] {-1, 1}, new long[] {0, 0}, new long[] {0, 9007199254740992L})) {
            assertThrows(DataAccessException.class, () -> transaction().executeWithoutResult(status ->
                    repository.insertIfAbsent(userId, DEVICE, publishedId, invalid[0], invalid[1], LocalDateTime.now())));
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from user_playback_progress", Integer.class));
    }

    @Test
    void should_keep_users_and_devices_independent() {
        var time = LocalDateTime.of(2026, 11, 15, 0, 0);
        transaction().executeWithoutResult(status -> {
            repository.insertIfAbsent(userId, DEVICE, publishedId, 10, 1, time);
            repository.insertIfAbsent(adminId, DEVICE, draftId, 20, 2, time);
            repository.insertIfAbsent(userId, OTHER_DEVICE, publishedId, 30, 3, time);
        });
        assertEquals(1L, progress(userId, DEVICE).get("last_sequence"));
        assertEquals(2L, progress(adminId, DEVICE).get("last_sequence"));
        assertEquals(3L, progress(userId, OTHER_DEVICE).get("last_sequence"));
    }
}

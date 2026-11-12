package org.example.music.favorite;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.TimeZone;
import org.example.music.favorite.repository.FavoriteRepository;
import org.example.music.track.domain.TrackStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FavoriteRepositoryIntegrationTest extends FavoriteIntegrationSupport {
    @Autowired FavoriteRepository favorites;

    @Test
    void should_keep_first_audit_fields_when_favorited_again() {
        LocalDateTime first = LocalDateTime.of(2026, 11, 12, 0, 0, 0, 123456000);
        transaction().executeWithoutResult(status -> favorites.addIfAbsent(userId, publishedId, first));
        var original = jdbc.queryForMap("select * from user_favorites where user_id=? and track_id=?", userId, publishedId);
        assertEquals(userId, original.get("created_by"));
        assertEquals(userId, original.get("updated_by"));
        assertEquals(first, original.get("created_at"));
        assertEquals(first, original.get("updated_at"));
        transaction().executeWithoutResult(status -> favorites.addIfAbsent(userId, publishedId, first.plusDays(1)));
        assertEquals(1, count(publishedId));
        assertEquals(original, jdbc.queryForMap("select * from user_favorites where user_id=? and track_id=?", userId, publishedId));
    }

    @Test
    void should_apply_exactly_two_cascade_foreign_keys_and_composite_primary_key() {
        assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where version='9' and success=1", Integer.class));
        assertEquals(Set.of("user_id", "track_id"), Set.copyOf(jdbc.queryForList("""
                select column_name from information_schema.key_column_usage
                where table_schema=database() and table_name='user_favorites' and referenced_table_name is not null
                """, String.class)));
        assertEquals(2, jdbc.queryForObject("""
                select count(*) from information_schema.referential_constraints
                where constraint_schema=database() and table_name='user_favorites' and delete_rule='CASCADE'
                """, Integer.class));
        assertEquals(java.util.List.of("user_id", "track_id"), jdbc.queryForList("""
                select column_name from information_schema.key_column_usage
                where table_schema=database() and table_name='user_favorites' and constraint_name='PRIMARY'
                order by ordinal_position
                """, String.class));
        assertEquals(6, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema=database() and table_name='user_favorites' and is_nullable='NO'
                """, Integer.class));
        var columnTypes = jdbc.queryForList("""
                select column_name,data_type from information_schema.columns
                where table_schema=database() and table_name='user_favorites'
                """).stream().collect(java.util.stream.Collectors.toMap(
                        row -> (String) row.get("COLUMN_NAME"), row -> (String) row.get("DATA_TYPE")));
        assertEquals(java.util.Map.of("user_id", "int", "track_id", "bigint", "created_by", "int",
                "updated_by", "int", "created_at", "datetime", "updated_at", "datetime"), columnTypes);
        assertEquals(2, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema=database() and table_name='user_favorites' and datetime_precision=6
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from information_schema.statistics
                where table_schema=database() and table_name='user_favorites'
                and index_name='ix_user_favorites_track_id' and column_name='track_id'
                """, Integer.class));
    }

    @Test
    void should_cascade_only_favorites_for_deleted_track() {
        transaction().executeWithoutResult(status -> {
            favorites.addIfAbsent(userId, publishedId, LocalDateTime.now());
            favorites.addIfAbsent(adminId, publishedId, LocalDateTime.now());
            favorites.addIfAbsent(userId, draftId, LocalDateTime.now());
        });
        jdbc.update("delete from tracks where id=?", publishedId);
        assertEquals(0, count(publishedId));
        assertEquals(1, count(draftId));
    }

    @Test
    void should_cascade_favorites_for_deleted_user() {
        transaction().executeWithoutResult(status -> {
            favorites.addIfAbsent(userId, publishedId, LocalDateTime.now());
            favorites.addIfAbsent(adminId, publishedId, LocalDateTime.now());
        });
        jdbc.update("delete from users where id=?", userId);
        assertEquals(1, count(publishedId));
        assertEquals(adminId, jdbc.queryForObject("select user_id from user_favorites where track_id=?", Integer.class, publishedId));
    }

    @Test
    void should_read_existing_status_and_missing_track_under_lock() {
        transaction().executeWithoutResult(status -> {
            assertEquals(TrackStatus.PUBLISHED, favorites.lockTrackStatus(publishedId).orElseThrow());
            assertEquals(TrackStatus.DRAFT, favorites.lockTrackStatus(draftId).orElseThrow());
            assertEquals(TrackStatus.ARCHIVED, favorites.lockTrackStatus(archivedId).orElseThrow());
            assertTrue(favorites.lockTrackStatus(Long.MAX_VALUE).isEmpty());
        });
    }

    @Test
    void should_preserve_utc_microseconds_under_non_utc_default_timezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            transaction().executeWithoutResult(status -> favorites.addIfAbsent(userId, publishedId,
                    LocalDateTime.parse("2026-11-12T00:00:00.123456")));
            assertEquals("2026-11-12 00:00:00.123456", jdbc.queryForObject(
                    "select cast(created_at as char) from user_favorites where track_id=?", String.class, publishedId));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}

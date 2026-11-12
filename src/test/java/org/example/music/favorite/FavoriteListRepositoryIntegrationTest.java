package org.example.music.favorite;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import org.example.music.favorite.repository.FavoriteListRepository;
import org.example.music.favorite.service.FavoriteListSort;
import org.example.music.track.domain.TrackStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class FavoriteListRepositoryIntegrationTest extends FavoriteIntegrationSupport {
    @Autowired FavoriteListRepository favorites;

    void favorite(int owner, long track, String time) {
        jdbc.update("insert into user_favorites values(?,?,cast(? as datetime(6)),cast(? as datetime(6)),?,?)",
                owner, track, time, time, owner, owner);
    }

    @Test
    void should_filter_owner_and_status_before_paging() {
        favorite(userId, publishedId, "2026-11-12 08:30:00.123456");
        favorite(userId, draftId, "2026-11-12 09:30:00.123456");
        favorite(userId, archivedId, "2026-11-12 10:30:00.123456");
        favorite(adminId, publishedId, "2026-11-12 11:30:00.123456");
        var rows = favorites.findRows(userId, TrackStatus.PUBLISHED, 0, 1, new FavoriteListSort("favoritedAt", false));
        assertEquals(List.of(publishedId), rows.stream().map(row -> row.id()).toList());
        assertEquals(1, favorites.count(userId, TrackStatus.PUBLISHED));
        assertEquals(3, favorites.count(userId, null));
        assertEquals(1, favorites.count(adminId, null));
        assertTrue(favorites.findRows(userId, null, 3, 1, new FavoriteListSort("id", true)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Pacific/Honolulu"})
    void should_read_microseconds_independently_of_default_timezone(String zone) {
        favorite(userId, publishedId, "2026-11-12 08:30:00.123456");
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            var row = favorites.findRows(userId, null, 0, 20, new FavoriteListSort("id", true)).getFirst();
            assertEquals(Instant.parse("2026-11-12T08:30:00.123456Z"), row.favoritedAt());
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "title", "artistName", "createdAt", "favoritedAt"})
    void should_sort_each_field_and_break_ties_by_id(String field) {
        for (long track : List.of(publishedId, draftId, archivedId)) {
            favorite(userId, track, "2026-11-12 08:30:00.123456");
            jdbc.update("update tracks set title='Same',artist_name='Same',created_at='2026-01-01' where id=?", track);
        }
        for (boolean asc : List.of(true, false)) {
            var rows = favorites.findRows(userId, null, 0, 20, new FavoriteListSort(field, asc));
            assertEquals(asc ? List.of(publishedId, draftId, archivedId) : List.of(archivedId, draftId, publishedId),
                    rows.stream().map(row -> row.id()).toList());
        }
        jdbc.update("update tracks set title='A',artist_name='A',created_at='2025-01-01' where id=?", archivedId);
        jdbc.update("update user_favorites set created_at='2027-01-01' where user_id=? and track_id=?", userId, archivedId);
        var first = favorites.findRows(userId, null, 0, 1, new FavoriteListSort(field, true)).getFirst();
        assertEquals(field.equals("id") || field.equals("favoritedAt") ? publishedId : archivedId, first.id());
    }
}

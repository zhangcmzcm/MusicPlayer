package org.example.music.favorite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.Set;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackListCoverRow;
import org.example.music.favorite.repository.FavoriteListRepository;
import org.example.music.favorite.repository.FavoriteListRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FavoriteListQueryServiceTest {
    private final FavoriteListRepository tracks = mock(FavoriteListRepository.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final FavoriteListQueryService service = new FavoriteListQueryService(tracks,assets);
    private final FavoriteListParameters params = new FavoriteListParameters(0,20,new FavoriteListSort("id",false),null);

    private FavoriteListRow row(long id, Long cover) {
        return new FavoriteListRow(id,"title","artist",null,null,TrackStatus.PUBLISHED,cover,java.time.Instant.parse("2026-11-12T08:30:00.123456Z"));
    }

    @Test
    void should_batch_shared_cover_ids_and_use_each_track_url() {
        when(tracks.findRows(7,null,0,20,params.sort())).thenReturn(List.of(row(42,3L),row(43,3L),row(44,null)));
        when(tracks.count(7,null)).thenReturn(3L);
        when(assets.findListCovers(Set.of(3L))).thenReturn(List.of(new TrackListCoverRow(3L,MediaAssetKind.COVER,"image/png",15L,"a".repeat(64),"private","secret")));
        var result = service.query(7,params,null);
        assertEquals("/api/v1/tracks/42/cover",result.items().get(0).cover().url());
        assertEquals("/api/v1/tracks/43/cover",result.items().get(1).cover().url());
        assertNull(result.items().get(2).cover());
        assertNull(result.items().get(0).albumName());
        assertNull(result.items().get(0).durationMs());
        assertEquals(1L,result.totalPages());
        assertFalse(result.hasNext());
        verify(assets).findListCovers(Set.of(3L));
        verifyNoMoreInteractions(assets);
    }

    @ParameterizedTest
    @ValueSource(longs = {0,20,21,9223372036854775807L})
    void should_calculate_long_page_counts_without_overflow(long total) {
        when(tracks.findRows(7,null,0,20,params.sort())).thenReturn(total == 0 ? List.of() : List.of(row(1,null)));
        when(tracks.count(7,null)).thenReturn(total);
        var result = service.query(7,params,null);
        assertEquals(total / 20 + (total % 20 == 0 ? 0 : 1),result.totalPages());
        assertEquals(total > 20,result.hasNext());
        verifyNoInteractions(assets);
    }

    @Test
    void should_keep_total_pages_as_long_at_maximum_count() {
        var single = new FavoriteListParameters(0,1,params.sort(),null);
        when(tracks.findRows(7,null,0,1,params.sort())).thenReturn(List.of(row(1,null)));
        when(tracks.count(7,null)).thenReturn(Long.MAX_VALUE);
        var result = service.query(7,single,null);
        assertEquals(Long.MAX_VALUE,result.totalPages());
        assertTrue(result.hasNext());
    }

    @Test
    void should_preserve_total_on_out_of_range_page() {
        var later = new FavoriteListParameters(4,20,params.sort(),TrackStatus.PUBLISHED);
        when(tracks.findRows(7,TrackStatus.PUBLISHED,80,20,params.sort())).thenReturn(List.of());
        when(tracks.count(7,TrackStatus.PUBLISHED)).thenReturn(3L);
        var result = service.query(7,later,TrackStatus.PUBLISHED);
        assertTrue(result.items().isEmpty());
        assertEquals(3L,result.totalElements());
        assertEquals(1L,result.totalPages());
        assertFalse(result.hasNext());
        verifyNoInteractions(assets);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing","kind","mime","size","digest","bucket","key"})
    void should_fail_entire_page_for_inconsistent_referenced_asset(String fault) {
        when(tracks.findRows(7,null,0,20,params.sort())).thenReturn(List.of(row(42,3L)));
        when(tracks.count(7,null)).thenReturn(1L);
        var asset = new TrackListCoverRow(3L,fault.equals("kind") ? MediaAssetKind.AUDIO : MediaAssetKind.COVER,
                fault.equals("mime") ? "text/plain" : "image/png",fault.equals("size") ? 0L : 15L,
                fault.equals("digest") ? "invalid" : "a".repeat(64),fault.equals("bucket") ? "" : "private",fault.equals("key") ? "" : "secret");
        when(assets.findListCovers(Set.of(3L))).thenReturn(fault.equals("missing") ? List.of() : List.of(asset));
        assertThrows(IllegalStateException.class, () -> service.query(7,params,null));
    }
}

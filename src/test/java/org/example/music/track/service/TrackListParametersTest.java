package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.example.music.track.exception.TrackListValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.util.LinkedMultiValueMap;

class TrackListParametersTest {
    @Test
    void should_apply_defaults_only_for_missing_parameters() {
        var p = TrackListParameters.from(new LinkedMultiValueMap<>());
        assertEquals(0, p.page());
        assertEquals(20, p.size());
        assertEquals("id", p.sort().property());
        assertFalse(p.sort().ascending());
        assertNull(p.requestedStatus());
    }

    @ParameterizedTest
    @CsvSource(value = {"size|0", "size|101", "page|-1", "page|2147483648", "page|1.5", "page|abc", "status|published", "status|ALL", "status|DRAFT,PUBLISHED", "sort|random,asc", "sort|title,ASC", "sort|title", "sort|title,asc,id,desc", "sort|id3Metadata,asc", "unknown|hello"}, delimiter = '|')
    void should_reject_invalid_or_unknown_parameters(String key, String value) {
        var raw = new LinkedMultiValueMap<String, String>();
        raw.add(key, value);
        assertThrows(TrackListValidationException.class, () -> TrackListParameters.from(raw));
    }

    @ParameterizedTest
    @CsvSource({"100,100,true", "101,100,false", "10000,1,true", "2147483647,100,false"})
    void should_enforce_offset_without_integer_overflow(int page, int size, boolean allowed) {
        var raw = new LinkedMultiValueMap<String, String>();
        raw.add("page", String.valueOf(page)); raw.add("size", String.valueOf(size));
        if (allowed) { assertEquals(page, TrackListParameters.from(raw).page()); }
        else { assertThrows(TrackListValidationException.class, () -> TrackListParameters.from(raw)); }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"page", "size", "sort", "status", "q"})
    void should_reject_empty_and_repeated_parameters(String key) {
        var raw = new LinkedMultiValueMap<String, String>();
        raw.add(key, "");
        assertThrows(TrackListValidationException.class, () -> TrackListParameters.from(raw));
        raw.clear(); raw.add(key, "1"); raw.add(key, "1");
        assertThrows(TrackListValidationException.class, () -> TrackListParameters.from(raw));
    }

    @Test
    void should_split_unicode_whitespace_and_keep_cross_field_terms() {
        assertEquals(List.of("周杰伦", "晴天"), parseQ("\u00a0周杰伦\u3000\t晴天\n").searchTerms());
        assertTrue(parseQ("晴").hasSearch());
        assertFalse(TrackListParameters.from(new LinkedMultiValueMap<>()).hasSearch());
    }

    @Test
    void should_count_code_points_before_normalization() {
        assertEquals(List.of("😀".repeat(100)), parseQ("😀".repeat(100)).searchTerms());
        assertThrows(TrackListValidationException.class, () -> parseQ("😀".repeat(101)));
        assertEquals(100, parseQ("晴".repeat(100)).searchTerms().getFirst().length());
        assertThrows(TrackListValidationException.class, () -> parseQ(" ".repeat(100) + "晴"));
    }

    @Test
    void should_enforce_five_occurrences_including_duplicate_terms() {
        assertEquals(List.of("晴", "晴", "晴", "晴", "晴"), parseQ("晴 晴 晴 晴 晴").searchTerms());
        assertThrows(TrackListValidationException.class, () -> parseQ("晴 晴 晴 晴 晴 晴"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", " ", "\u00a0", "\u3000\t\n", "\u0085"})
    void should_reject_blank_q(String rawQ) {
        assertThrows(TrackListValidationException.class, () -> parseQ(rawQ));
    }

    @Test
    void should_keep_search_terms_immutable_and_validate_direct_construction() {
        var terms = new ArrayList<>(List.of("晴"));
        var parameters = new TrackListParameters(0, 20, new TrackListSort("id", false), null, terms);
        terms.clear();
        assertEquals(List.of("晴"), parameters.searchTerms());
        assertThrows(UnsupportedOperationException.class, () -> parameters.searchTerms().add("天"));
        assertThrows(TrackListValidationException.class,
                () -> new TrackListParameters(0, 20, parameters.sort(), null, null));
        for (var invalid : List.of(List.of(""), List.of("晴 天"), List.of("\u00a0"), List.of("晴".repeat(101)))) {
            assertThrows(TrackListValidationException.class,
                    () -> new TrackListParameters(0, 20, parameters.sort(), null, invalid));
        }
    }

    private TrackListParameters parseQ(String rawQ) {
        var raw = new LinkedMultiValueMap<String, String>();
        raw.add("q", rawQ);
        return TrackListParameters.from(raw);
    }
}

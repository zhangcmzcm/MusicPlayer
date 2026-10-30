package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    @CsvSource(value = {"size|0", "size|101", "page|-1", "page|2147483648", "page|1.5", "page|abc", "status|published", "status|ALL", "status|DRAFT,PUBLISHED", "sort|random,asc", "sort|title,ASC", "sort|title", "sort|title,asc,id,desc", "sort|id3Metadata,asc", "q|hello"}, delimiter = '|')
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
    @org.junit.jupiter.params.provider.ValueSource(strings = {"page", "size", "sort", "status"})
    void should_reject_empty_and_repeated_parameters(String key) {
        var raw = new LinkedMultiValueMap<String, String>();
        raw.add(key, "");
        assertThrows(TrackListValidationException.class, () -> TrackListParameters.from(raw));
        raw.clear(); raw.add(key, "1"); raw.add(key, "1");
        assertThrows(TrackListValidationException.class, () -> TrackListParameters.from(raw));
    }
}

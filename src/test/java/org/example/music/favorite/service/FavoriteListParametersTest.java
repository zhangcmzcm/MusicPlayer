package org.example.music.favorite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.example.music.favorite.exception.FavoriteListValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.util.LinkedMultiValueMap;

class FavoriteListParametersTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"id", "title", "artistName", "createdAt", "favoritedAt"})
    void should_support_all_sort_fields_in_both_directions(String field) {
        for (String direction : java.util.List.of("asc", "desc")) {
            var raw = new LinkedMultiValueMap<String, String>();
            raw.add("sort", field + "," + direction);
            assertEquals(new FavoriteListSort(field, direction.equals("asc")), FavoriteListParameters.from(raw).sort());
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"DRAFT", "PUBLISHED", "ARCHIVED"})
    void should_accept_each_status(String status) {
        var raw = new LinkedMultiValueMap<String, String>();
        raw.add("status", status);
        assertEquals(status, FavoriteListParameters.from(raw).requestedStatus().name());
    }

    @Test
    void should_preserve_numeric_parsing_and_reject_blank_or_identity_parameters() {
        for (String value : java.util.List.of("+1", "01")) {
            var raw = new LinkedMultiValueMap<String, String>();
            raw.add("page", value);
            assertEquals(1, FavoriteListParameters.from(raw).page());
        }
        for (String key : java.util.List.of("page", "size", "sort", "status", "userId")) {
            for (String value : java.util.List.of(" ", " 1", "1 ")) {
                var raw = new LinkedMultiValueMap<String, String>();
                raw.add(key, value);
                assertThrows(FavoriteListValidationException.class, () -> FavoriteListParameters.from(raw));
            }
        }
        assertThrows(FavoriteListValidationException.class, () -> new FavoriteListParameters(0, 20, null, null));
        assertThrows(FavoriteListValidationException.class, () -> new FavoriteListSort("unsafe", false));
    }

    @Test
    void should_apply_defaults_only_for_missing_parameters() {
        var p = FavoriteListParameters.from(new LinkedMultiValueMap<>());
        assertEquals(0, p.page());
        assertEquals(20, p.size());
        assertEquals("favoritedAt", p.sort().property());
        assertFalse(p.sort().ascending());
        assertNull(p.requestedStatus());
    }

    @ParameterizedTest
    @CsvSource(value = {"size|0", "size|101", "page|-1", "page|2147483648", "page|1.5", "page|abc", "status|published", "status|ALL", "status|DRAFT,PUBLISHED", "sort|random,asc", "sort|title,ASC", "sort|title", "sort|title,asc,id,desc", "sort|id3Metadata,asc", "q|hello"}, delimiter = '|')
    void should_reject_invalid_or_unknown_parameters(String key, String value) {
        var raw = new LinkedMultiValueMap<String, String>();
        raw.add(key, value);
        assertThrows(FavoriteListValidationException.class, () -> FavoriteListParameters.from(raw));
    }

    @ParameterizedTest
    @CsvSource({"100,100,true", "101,100,false", "10000,1,true", "2147483647,100,false"})
    void should_enforce_offset_without_integer_overflow(int page, int size, boolean allowed) {
        var raw = new LinkedMultiValueMap<String, String>();
        raw.add("page", String.valueOf(page)); raw.add("size", String.valueOf(size));
        if (allowed) { assertEquals(page, FavoriteListParameters.from(raw).page()); }
        else { assertThrows(FavoriteListValidationException.class, () -> FavoriteListParameters.from(raw)); }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"page", "size", "sort", "status"})
    void should_reject_empty_and_repeated_parameters(String key) {
        var raw = new LinkedMultiValueMap<String, String>();
        raw.add(key, "");
        assertThrows(FavoriteListValidationException.class, () -> FavoriteListParameters.from(raw));
        raw.clear(); raw.add(key, "1"); raw.add(key, "1");
        assertThrows(FavoriteListValidationException.class, () -> FavoriteListParameters.from(raw));
    }
}

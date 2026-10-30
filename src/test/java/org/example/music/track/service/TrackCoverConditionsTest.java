package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TrackCoverConditionsTest {
    @ParameterizedTest
    @CsvSource(value = {"null|false", "|false", "*|true", "\"abc\"|true", "W/\"abc\"|true", "\"other\", \"abc\"|true", "\"other\"|false", "abc|false", "W/abc|false", "\"abc\"junk|false", "*, \"abc\"|false"}, delimiter = '|', nullValues = "null")
    void should_match_only_valid_get_conditions(String header, boolean expected) {
        assertEquals(expected, TrackCoverConditions.matches(header, "\"abc\""));
    }
}

package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.example.music.track.exception.TrackRangeNotSatisfiableException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class TrackStreamRangeParserTest {
    @ParameterizedTest
    @CsvSource(value={"bytes=0-0|0|0", "bytes=0-9|0|9", "bytes=0-999999999999999999999999|0|9", "bytes=5-|5|9", "bytes=-3|7|9", "bytes=-999999999999999999999999|0|9", "BYTES=1-2|1|2"},delimiter='|')
    void should_normalize_valid_ranges(String header,long start,long end) {
        var result=TrackStreamRangeParser.parse(List.of(header),10);
        assertEquals(start,result.start()); assertEquals(end,result.end()); assertEquals(end-start+1,result.length());
    }
    @ParameterizedTest
    @ValueSource(strings={"bytes=10-", "bytes=999999999999999999999999-", "bytes=9-2", "bytes=-0", "bytes=abc", "bytes=", "bytes=1--2", "bytes=1 -2"})
    void should_reject_invalid_single_bytes_range(String header) {
        assertEquals(10,assertThrows(TrackRangeNotSatisfiableException.class,()->TrackStreamRangeParser.parse(List.of(header),10)).getTotalSize());
    }
    @Test
    void should_ignore_absent_unknown_and_multi_ranges() {
        assertNull(TrackStreamRangeParser.parse(null,10));
        assertNull(TrackStreamRangeParser.parse(List.of("pages=1-2"),10));
        assertNull(TrackStreamRangeParser.parse(List.of("bytes=0-1,4-5"),10));
        assertNull(TrackStreamRangeParser.parse(List.of("bytes=0-1","bytes=4-5"),10));
    }
}

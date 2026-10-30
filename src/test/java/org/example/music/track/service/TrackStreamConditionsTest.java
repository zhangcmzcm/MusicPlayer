package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.example.music.track.exception.TrackPreconditionFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

class TrackStreamConditionsTest {
    private final Instant modified=Instant.parse("2026-10-01T00:00:00Z");
    private final String etag="\"abc\"";
    @ParameterizedTest
    @ValueSource(strings={"\"abc\"","W/\"abc\"","\"other\", \"abc\"","*"})
    void should_match_weak_none_match(String value) {
        var h=new HttpHeaders();h.set("If-None-Match",value);
        assertEquals(304,TrackStreamConditions.evaluate(h,etag,modified));
    }
    @ParameterizedTest
    @ValueSource(strings={"W/\"abc\"","\"other\"","invalid","\"abc\"junk"})
    void should_reject_nonmatching_strong_precondition(String value) {
        var h=new HttpHeaders();h.set("If-Match",value);h.set("If-None-Match","*");
        assertThrows(TrackPreconditionFailedException.class,()->TrackStreamConditions.evaluate(h,etag,modified));
    }
    @Test
    void should_observe_header_precedence_and_seconds() {
        var h=new HttpHeaders();h.setIfUnmodifiedSince(modified.minusSeconds(1));
        assertThrows(TrackPreconditionFailedException.class,()->TrackStreamConditions.evaluate(h,etag,modified));
        h.set("If-Match",etag);h.set("If-None-Match",etag);
        assertEquals(304,TrackStreamConditions.evaluate(h,etag,modified));
        h.clear();h.setIfModifiedSince(modified);
        assertEquals(304,TrackStreamConditions.evaluate(h,etag,modified));
        h.set("If-None-Match","\"other\"");
        assertEquals(200,TrackStreamConditions.evaluate(h,etag,modified));
        h.clear();h.set("If-Modified-Since","nonsense");
        assertEquals(200,TrackStreamConditions.evaluate(h,etag,modified));
    }
    @Test
    void should_allow_range_only_with_reliable_exact_validator() {
        assertTrue(TrackStreamConditions.allowsRange(null,etag,modified,false));
        assertTrue(TrackStreamConditions.allowsRange(List.of(etag),etag,modified,false));
        assertFalse(TrackStreamConditions.allowsRange(List.of("W/"+etag),etag,modified,true));
        assertFalse(TrackStreamConditions.allowsRange(List.of(etag,etag),etag,modified,true));
        var h=new HttpHeaders();h.setLastModified(modified);
        String date=h.getFirst("Last-Modified");
        assertTrue(TrackStreamConditions.allowsRange(List.of(date),etag,modified,true));
        assertFalse(TrackStreamConditions.allowsRange(List.of(date),etag,modified,false));
        assertFalse(TrackStreamConditions.allowsRange(List.of(date),etag,modified.plusSeconds(1),true));
    }
}

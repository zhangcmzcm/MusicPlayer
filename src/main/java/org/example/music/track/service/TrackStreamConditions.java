package org.example.music.track.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.example.music.track.exception.TrackPreconditionFailedException;
import org.springframework.http.HttpHeaders;

public final class TrackStreamConditions {
    private static final Pattern TAG = Pattern.compile("(?:W/)?\"[\\x21\\x23-\\x7e\\x80-\\xff]*\"");
    private TrackStreamConditions() { }

    public static int evaluate(HttpHeaders headers, String etag, Instant lastModified) {
        long modified = lastModified.getEpochSecond();
        if (headers.containsHeader(HttpHeaders.IF_MATCH)) {
            if (!matches(headers.get(HttpHeaders.IF_MATCH),etag,false)) { throw new TrackPreconditionFailedException(); }
        } else {
            long date = date(headers,HttpHeaders.IF_UNMODIFIED_SINCE);
            if (date >= 0 && modified > date / 1000) { throw new TrackPreconditionFailedException(); }
        }
        if (headers.containsHeader(HttpHeaders.IF_NONE_MATCH)) {
            return matches(headers.get(HttpHeaders.IF_NONE_MATCH),etag,true) ? 304 : 200;
        }
        long date = date(headers,HttpHeaders.IF_MODIFIED_SINCE);
        return date >= 0 && modified <= date / 1000 ? 304 : 200;
    }

    public static boolean allowsRange(List<String> values, String etag, Instant lastModified, boolean dateStrong) {
        if (values == null || values.isEmpty()) { return true; }
        if (values.size() != 1) { return false; }
        String value = values.getFirst().strip();
        if (value.equals(etag)) { return true; }
        if (!dateStrong || value.startsWith("W/") || value.startsWith("\"")) { return false; }
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.IF_RANGE,value);
        long date = date(headers,HttpHeaders.IF_RANGE);
        return date >= 0 && date / 1000 == lastModified.getEpochSecond();
    }

    private static boolean matches(List<String> values, String etag, boolean weak) {
        if (values == null) { return false; }
        String raw = String.join(",",values).strip();
        if (raw.equals("*")) { return true; }
        var matcher = TAG.matcher(raw);
        int offset = 0;
        var tags = new ArrayList<String>();
        while (offset < raw.length()) {
            matcher.region(offset,raw.length());
            if (!matcher.lookingAt()) { return false; }
            tags.add(matcher.group());
            offset = matcher.end();
            while (offset < raw.length() && (raw.charAt(offset)==' ' || raw.charAt(offset)=='	')) { offset++; }
            if (offset == raw.length()) {
                return tags.stream().anyMatch(tag -> (weak && tag.startsWith("W/") ? tag.substring(2) : tag).equals(etag));
            }
            if (raw.charAt(offset++) != ',') { return false; }
            while (offset < raw.length() && (raw.charAt(offset)==' ' || raw.charAt(offset)=='	')) { offset++; }
        }
        return false;
    }

    private static long date(HttpHeaders headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.size() != 1) { return -1; }
        try { return headers.getFirstDate(name); }
        catch (IllegalArgumentException exception) { return -1; }
    }
}

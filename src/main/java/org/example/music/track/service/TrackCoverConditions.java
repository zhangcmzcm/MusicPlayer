package org.example.music.track.service;

import java.util.regex.Pattern;

public final class TrackCoverConditions {
    private static final Pattern TAG = Pattern.compile("(?:W/)?\"[\\x21\\x23-\\x7e\\x80-\\xff]*\"");

    private TrackCoverConditions() {
    }

    public static boolean matches(String header, String etag) {
        if (header == null || header.isBlank()) {
            return false;
        }
        String value = header.strip();
        if (value.equals("*")) {
            return true;
        }
        var matcher = TAG.matcher(value);
        int offset = 0;
        boolean matched = false;
        while (offset < value.length()) {
            matcher.region(offset, value.length());
            if (!matcher.lookingAt()) {
                return false;
            }
            String tag = matcher.group();
            matched |= (tag.startsWith("W/") ? tag.substring(2) : tag).equals(etag);
            offset = matcher.end();
            while (offset < value.length() && (value.charAt(offset) == ' ' || value.charAt(offset) == '	')) {
                offset++;
            }
            if (offset == value.length()) {
                return matched;
            }
            if (value.charAt(offset++) != ',') {
                return false;
            }
            while (offset < value.length() && (value.charAt(offset) == ' ' || value.charAt(offset) == '	')) {
                offset++;
            }
            if (offset == value.length()) {
                return false;
            }
        }
        return false;
    }
}

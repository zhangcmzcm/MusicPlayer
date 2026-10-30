package org.example.music.track.service;

import java.util.Set;
import org.example.music.track.exception.TrackListValidationException;

public record TrackListSort(String property, boolean ascending) {
    private static final Set<String> FIELDS = Set.of("id", "title", "artistName", "createdAt");

    public TrackListSort {
        if (property == null || !FIELDS.contains(property)) {
            throw new TrackListValidationException();
        }
    }

    static TrackListSort parse(String raw) {
        String[] parts = raw.split(",", -1);
        if (parts.length != 2 || !(parts[1].equals("asc") || parts[1].equals("desc"))) {
            throw new TrackListValidationException();
        }
        return new TrackListSort(parts[0], parts[1].equals("asc"));
    }
}

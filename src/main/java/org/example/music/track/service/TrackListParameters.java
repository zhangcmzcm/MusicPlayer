package org.example.music.track.service;

import java.util.Set;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackListValidationException;
import org.springframework.util.MultiValueMap;

public record TrackListParameters(int page, int size, TrackListSort sort, TrackStatus requestedStatus) {
    private static final Set<String> NAMES = Set.of("page", "size", "sort", "status");

    public TrackListParameters {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > 10000 || sort == null) {
            throw new TrackListValidationException();
        }
    }

    public static TrackListParameters from(MultiValueMap<String, String> raw) {
        raw.forEach((key, values) -> {
            if (!NAMES.contains(key) || values == null || values.size() != 1
                    || values.getFirst() == null || values.getFirst().isBlank()) {
                throw new TrackListValidationException();
            }
        });
        try {
            int page = raw.containsKey("page") ? Integer.parseInt(raw.getFirst("page")) : 0;
            int size = raw.containsKey("size") ? Integer.parseInt(raw.getFirst("size")) : 20;
            TrackListSort sort = TrackListSort.parse(raw.containsKey("sort") ? raw.getFirst("sort") : "id,desc");
            TrackStatus status = raw.containsKey("status") ? TrackStatus.valueOf(raw.getFirst("status")) : null;
            return new TrackListParameters(page, size, sort, status);
        } catch (IllegalArgumentException exception) {
            throw new TrackListValidationException();
        }
    }
}

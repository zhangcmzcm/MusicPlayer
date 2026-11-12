package org.example.music.favorite.service;

import java.util.Set;
import org.example.music.track.domain.TrackStatus;
import org.example.music.favorite.exception.FavoriteListValidationException;
import org.springframework.util.MultiValueMap;

public record FavoriteListParameters(int page, int size, FavoriteListSort sort, TrackStatus requestedStatus) {
    private static final Set<String> NAMES = Set.of("page", "size", "sort", "status");

    public FavoriteListParameters {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > 10000 || sort == null) {
            throw new FavoriteListValidationException();
        }
    }

    public static FavoriteListParameters from(MultiValueMap<String, String> raw) {
        raw.forEach((key, values) -> {
            if (!NAMES.contains(key) || values == null || values.size() != 1
                    || values.getFirst() == null || values.getFirst().isBlank()) {
                throw new FavoriteListValidationException();
            }
        });
        try {
            int page = raw.containsKey("page") ? Integer.parseInt(raw.getFirst("page")) : 0;
            int size = raw.containsKey("size") ? Integer.parseInt(raw.getFirst("size")) : 20;
            FavoriteListSort sort = FavoriteListSort.parse(raw.containsKey("sort") ? raw.getFirst("sort") : "favoritedAt,desc");
            TrackStatus status = raw.containsKey("status") ? TrackStatus.valueOf(raw.getFirst("status")) : null;
            return new FavoriteListParameters(page, size, sort, status);
        } catch (IllegalArgumentException exception) {
            throw new FavoriteListValidationException();
        }
    }
}

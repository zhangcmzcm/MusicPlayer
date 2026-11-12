package org.example.music.favorite.service;

import java.util.Set;
import org.example.music.favorite.exception.FavoriteListValidationException;

public record FavoriteListSort(String property, boolean ascending) {
    private static final Set<String> FIELDS = Set.of("id", "title", "artistName", "createdAt", "favoritedAt");

    public FavoriteListSort {
        if (property == null || !FIELDS.contains(property)) {
            throw new FavoriteListValidationException();
        }
    }

    static FavoriteListSort parse(String raw) {
        String[] parts = raw.split(",", -1);
        if (parts.length != 2 || !(parts[1].equals("asc") || parts[1].equals("desc"))) {
            throw new FavoriteListValidationException();
        }
        return new FavoriteListSort(parts[0], parts[1].equals("asc"));
    }
}

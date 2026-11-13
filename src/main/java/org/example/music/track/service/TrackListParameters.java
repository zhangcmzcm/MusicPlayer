package org.example.music.track.service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackListValidationException;
import org.springframework.util.MultiValueMap;

public record TrackListParameters(int page, int size, TrackListSort sort, TrackStatus requestedStatus,
                                  List<String> searchTerms) {
    private static final Set<String> NAMES = Set.of("page", "size", "sort", "status", "q");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public TrackListParameters(int page, int size, TrackListSort sort, TrackStatus requestedStatus) {
        this(page, size, sort, requestedStatus, List.of());
    }

    public TrackListParameters {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > 10000 || sort == null) {
            throw new TrackListValidationException();
        }
        if (searchTerms == null || searchTerms.size() > 5) {
            throw new TrackListValidationException();
        }
        int length = Math.max(0, searchTerms.size() - 1);
        for (String term : searchTerms) {
            if (term == null || term.isEmpty() || WHITESPACE.matcher(term).find()) {
                throw new TrackListValidationException();
            }
            length += term.codePointCount(0, term.length());
            if (length > 100) {
                throw new TrackListValidationException();
            }
        }
        searchTerms = List.copyOf(searchTerms);
    }

    public boolean hasSearch() {
        return !searchTerms.isEmpty();
    }

    public static TrackListParameters from(MultiValueMap<String, String> raw) {
        raw.forEach((key, values) -> {
            if (!NAMES.contains(key) || values == null || values.size() != 1
                    || values.getFirst() == null || (!key.equals("q") && values.getFirst().isBlank())) {
                throw new TrackListValidationException();
            }
        });
        try {
            int page = raw.containsKey("page") ? Integer.parseInt(raw.getFirst("page")) : 0;
            int size = raw.containsKey("size") ? Integer.parseInt(raw.getFirst("size")) : 20;
            TrackListSort sort = TrackListSort.parse(raw.containsKey("sort") ? raw.getFirst("sort") : "id,desc");
            TrackStatus status = raw.containsKey("status") ? TrackStatus.valueOf(raw.getFirst("status")) : null;
            List<String> terms = raw.containsKey("q") ? parseSearchTerms(raw.getFirst("q")) : List.of();
            return new TrackListParameters(page, size, sort, status, terms);
        } catch (IllegalArgumentException exception) {
            throw new TrackListValidationException();
        }
    }

    private static List<String> parseSearchTerms(String raw) {
        if (raw.codePointCount(0, raw.length()) > 100) {
            throw new TrackListValidationException();
        }
        List<String> terms = Arrays.stream(WHITESPACE.split(raw))
                .filter(term -> !term.isEmpty()).toList();
        if (terms.isEmpty()) {
            throw new TrackListValidationException();
        }
        return terms;
    }
}

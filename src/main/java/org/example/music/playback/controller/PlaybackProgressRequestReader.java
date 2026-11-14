package org.example.music.playback.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.example.music.playback.dto.SavePlaybackProgressRequest;
import org.example.music.playback.exception.PlaybackProgressUnsupportedMediaTypeException;
import org.example.music.playback.exception.PlaybackProgressValidationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

@Component
public class PlaybackProgressRequestReader {
    private static final Set<String> FIELDS = Set.of("trackId", "positionSeconds", "sequence");
    private static final JsonFactory JSON = JsonFactory.builder().build();
    private final Validator validator;

    public PlaybackProgressRequestReader(Validator validator) {
        this.validator = validator;
    }

    public SavePlaybackProgressRequest read(HttpServletRequest request) {
        requireMediaType(request.getContentType());
        try (var parser = JSON.createParser(request.getInputStream())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new PlaybackProgressValidationException();
            }
            Map<String, Long> fields = new HashMap<>();
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = parser.currentName();
                if (!FIELDS.contains(name) || fields.containsKey(name)
                        || parser.nextToken() != JsonToken.VALUE_NUMBER_INT) {
                    throw new PlaybackProgressValidationException();
                }
                fields.put(name, parser.getLongValue());
            }
            if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null
                    || !fields.keySet().equals(FIELDS)) {
                throw new PlaybackProgressValidationException();
            }
            var result = new SavePlaybackProgressRequest(fields.get("trackId"),
                    fields.get("positionSeconds"), fields.get("sequence"));
            if (!validator.validate(result).isEmpty()) {
                throw new PlaybackProgressValidationException();
            }
            return result;
        } catch (IOException | tools.jackson.core.JacksonException failure) {
            throw new PlaybackProgressValidationException();
        }
    }

    private static void requireMediaType(String value) {
        try {
            if (value == null) {
                throw new PlaybackProgressUnsupportedMediaTypeException();
            }
            MediaType type = MediaType.parseMediaType(value);
            if (!"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype())) {
                throw new PlaybackProgressUnsupportedMediaTypeException();
            }
        } catch (IllegalArgumentException failure) {
            throw new PlaybackProgressUnsupportedMediaTypeException();
        }
    }
}

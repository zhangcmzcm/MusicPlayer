package org.example.music.user.dto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class UpdateCurrentUserRequestDeserializerTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void should_preserve_exact_username() {
        assertEquals("Alice_123", mapper.readValue("{\"username\":\"Alice_123\"}",
                UpdateCurrentUserRequest.class).username());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "[]", "\"alice\"", "123", "true", "{}",
            "{\"username\":null}", "{\"username\":123}", "{\"username\":true}",
            "{\"username\":[]}", "{\"username\":{}}", "{\"email\":null}",
            "{\"username\":\"alice\",\"email\":null}",
            "{\"username\":\"alice\",\"username\":\"alice\"}",
            "{\"username\":\"alice\",\"user\\u006eame\":\"bob\"}",
            "{\"Username\":\"alice\"}", "{\"username\":\"alice\"} {}",
            "{\"username\":\"alice\""
    })
    void should_reject_invalid_shape_type_unknown_fields_and_duplicate_keys(String json) {
        assertThrows(JacksonException.class, () -> mapper.readValue(json, UpdateCurrentUserRequest.class));
    }
}

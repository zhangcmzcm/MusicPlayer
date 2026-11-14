package org.example.music.playback.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.example.music.playback.dto.SavePlaybackProgressRequest;
import org.example.music.playback.exception.PlaybackProgressUnsupportedMediaTypeException;
import org.example.music.playback.exception.PlaybackProgressValidationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class PlaybackProgressRequestReaderTest {
    private static final String BODY = "{\"trackId\":123,\"positionSeconds\":83,\"sequence\":42}";
    private static ValidatorFactory validators;
    private static PlaybackProgressRequestReader reader;

    @BeforeAll
    static void setup() {
        validators = Validation.buildDefaultValidatorFactory();
        reader = new PlaybackProgressRequestReader(validators.getValidator());
    }

    @AfterAll
    static void close() {
        validators.close();
    }

    private SavePlaybackProgressRequest read(String body) {
        return reader.read(request(body, "application/json"));
    }

    private MockHttpServletRequest request(String body, String contentType) {
        var request = new MockHttpServletRequest();
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void should_read_three_integer_fields_in_any_order() {
        assertEquals(new SavePlaybackProgressRequest(123L, 83L, 42L),
                read(" {\"sequence\":42,\"trackId\":123,\"positionSeconds\":83} \n"));
    }

    @Test
    void should_accept_numeric_boundaries() {
        assertEquals(new SavePlaybackProgressRequest(Long.MAX_VALUE, Long.MAX_VALUE, 9007199254740991L),
                read("{\"trackId\":9223372036854775807,\"positionSeconds\":9223372036854775807,\"sequence\":9007199254740991}"));
        assertEquals(new SavePlaybackProgressRequest(1L, 0L, 1L),
                read("{\"trackId\":1,\"positionSeconds\":0,\"sequence\":1}"));
    }

    static Stream<String> invalidBodies() {
        return Stream.of("", "null", "[]", "{}", "{", BODY + "{}", BODY + " true",
                BODY.replace("\"trackId\":123,", ""),
                BODY.replace("\"positionSeconds\":83,", ""),
                "{\"trackId\":123,\"positionSeconds\":83}",
                BODY.replace("123", "null"), BODY.replace("83", "null"), BODY.replace("42", "null"),
                BODY.replace("123", "0"), BODY.replace("123", "-1"),
                BODY.replace("83", "-1"), BODY.replace("42", "0"), BODY.replace("42", "-1"),
                BODY.replace("123", "9223372036854775808"),
                BODY.replace("83", "9223372036854775808"),
                BODY.replace("42", "9007199254740992"),
                BODY.replace("123", "\"123\""), BODY.replace("83", "\"83\""), BODY.replace("42", "\"42\""),
                BODY.replace("123", "1.0"), BODY.replace("83", "1.0"), BODY.replace("42", "1.0"),
                BODY.replace("123", "1e0"), BODY.replace("83", "1e0"), BODY.replace("42", "1e0"),
                BODY.replace("83", "true"), BODY.replace("83", "[]"), BODY.replace("83", "{}"),
                BODY.replace("}", ",\"userId\":7}"),
                BODY.replace("}", ",\"trackId\":123}"),
                BODY.replace("}", ",\"\\u0074rackId\":123}"),
                BODY.replace("}", ",\"positionSeconds\":83}"),
                BODY.replace("}", ",\"sequence\":42}"));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void should_reject_invalid_or_ambiguous_json(String body) {
        assertThrows(PlaybackProgressValidationException.class, () -> read(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json;charset=UTF-8", "application/json; charset=ISO-8859-1", "APPLICATION/JSON"})
    void should_accept_json_media_parameters(String contentType) {
        assertEquals(new SavePlaybackProgressRequest(123L, 83L, 42L), reader.read(request(BODY, contentType)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "text/plain", "application/problem+json", "application/json-invalid", "invalid"})
    void should_reject_unsupported_media_type(String contentType) {
        assertThrows(PlaybackProgressUnsupportedMediaTypeException.class,
                () -> reader.read(request(BODY, contentType)));
    }

    @Test
    void should_reject_invalid_encoded_bytes() {
        var request = request(BODY, "application/json");
        request.setContent(new byte[] {'{', '"', (byte) 0xc3, '"', ':', '1', '}'});
        assertThrows(PlaybackProgressValidationException.class, () -> reader.read(request));
    }
}

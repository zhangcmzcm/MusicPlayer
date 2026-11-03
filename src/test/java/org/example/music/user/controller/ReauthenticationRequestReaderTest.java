package org.example.music.user.controller;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;
import org.example.music.user.domain.ReauthenticationMethod;
import org.example.music.user.dto.ReauthenticationResponse;
import org.example.music.user.exception.ReauthenticationValidationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ReauthenticationRequestReaderTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final ReauthenticationRequestReader reader = new ReauthenticationRequestReader(VALIDATORS.getValidator());
    private static final String ID = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @AfterAll static void close() { VALIDATORS.close(); }

    private MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @ParameterizedTest @ValueSource(ints = {1, 7, 8, 128})
    void should_accept_historical_password_lengths(int length) {
        var result = reader.read(request("{\"method\":\"PASSWORD\",\"password\":\"" + "a".repeat(length) + "\"}"));
        assertEquals(ReauthenticationMethod.PASSWORD, result.method());
        assertEquals(length, result.password().length());
    }

    @Test void should_preserve_non_ascii_and_leading_and_trailing_spaces() {
        assertEquals(" 中文密码 ", reader.read(request("{\"password\":\" 中文密码 \",\"method\":\"PASSWORD\"}")).password());
        var value = reader.read(request("{\"method\":\"EMAIL_CODE\",\"challengeId\":\"" + ID + "\",\"code\":\"012345\"}"));
        assertEquals("012345", value.code());
        assertEquals(ID, value.challengeId());
    }

    static Stream<String> invalidBodies() {
        return Stream.of("", " ", "null", "{}", "[]", "true", "123", "\"str\"", "{",
                "{\"method\":\"PASSWORD\",\"password\":\"a\"} {}",
                "{\"method\":\"PASSWORD\",\"method\":\"PASSWORD\",\"password\":\"a\"}",
                "{\"method\":\"PASSWORD\",\"password\":\"a\",\"pass\\u0077ord\":\"a\"}",
                "{\"method\":\"password\",\"password\":\"a\"}",
                "{\"method\":null,\"password\":\"a\"}",
                "{\"method\":\"PASSWORD\"}", "{\"method\":\"PASSWORD\",\"password\":null}",
                "{\"method\":\"PASSWORD\",\"password\":123}",
                "{\"method\":\"PASSWORD\",\"password\":{}}",
                "{\"method\":\"PASSWORD\",\"password\":true}",
                "{\"method\":\"PASSWORD\",\"password\":\"\"}",
                "{\"method\":\"PASSWORD\",\"password\":\"  \"}",
                "{\"method\":\"PASSWORD\",\"password\":\"" + "a".repeat(129) + "\"}",
                "{\"method\":\"PASSWORD\",\"password\":\"a\",\"userId\":1}",
                "{\"method\":\"PASSWORD\",\"password\":\"a\",\"code\":\"123456\"}",
                "{\"method\":\"EMAIL_CODE\",\"challengeId\":\"" + ID + "\",\"code\":123456}",
                "{\"method\":\"EMAIL_CODE\",\"challengeId\":\"" + ID + "\",\"code\":\"١٢٣٤٥٦\"}",
                "{\"method\":\"EMAIL_CODE\",\"challengeId\":\"" + ID + "\",\"code\":\"12345\"}",
                "{\"method\":\"EMAIL_CODE\",\"challengeId\":\"" + "A".repeat(42) + "B\",\"code\":\"123456\"}",
                "{\"method\":\"EMAIL_CODE\",\"challengeId\":\"" + ID + "=\",\"code\":\"123456\"}",
                "{\"method\":\"EMAIL_CODE\",\"challengeId\":\"" + ID + "\",\"code\":\"123456\",\"password\":\"a\"}",
                "{\"" + "x".repeat(128) + "\":\"a\"}", "{\"" + "x".repeat(129) + "\":\"a\"}");
    }

    @ParameterizedTest @MethodSource("invalidBodies")
    void should_reject_invalid_fields_duplicate_decoded_keys_multiple_roots_and_coercion(String body) {
        assertThrows(ReauthenticationValidationException.class, () -> reader.read(request(body)));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"text/plain", "application/xml", "bad", "application/json;charset=UTF-16"})
    void should_reject_invalid_media_type_before_reading_body(String mediaType) {
        var request = request("{\"method\":\"PASSWORD\",\"password\":\"a\"}"); request.setContentType(mediaType);
        assertThrows(ReauthenticationValidationException.class, () -> reader.read(request));
    }

    @Test void should_accept_case_insensitive_utf8_media_type_and_reject_invalid_utf8() {
        var request = request("{\"method\":\"PASSWORD\",\"password\":\"a\"}");
        request.setContentType("Application/JSON; charset=utf-8"); assertEquals("a", reader.read(request).password());
        request.setContent(new byte[]{(byte)0xc3, (byte)0x28});
        assertThrows(ReauthenticationValidationException.class, () -> reader.read(request));
    }

    @Test void should_redact_secret_string_representations() {
        var request = reader.read(request("{\"method\":\"EMAIL_CODE\",\"challengeId\":\"" + ID + "\",\"code\":\"012345\"}"));
        assertFalse(request.toString().contains(ID)); assertFalse(request.toString().contains("012345"));
        assertFalse(new ReauthenticationResponse("hidden-token", "Bearer", Instant.EPOCH, Instant.EPOCH).toString().contains("hidden-token"));
    }

    @Test void should_sanitize_unexpected_validation_provider_failure() {
        var validator=org.mockito.Mockito.mock(jakarta.validation.Validator.class);
        org.mockito.Mockito.when(validator.validate(org.mockito.ArgumentMatchers.any())).thenThrow(new jakarta.validation.ValidationException("secret-password",new RuntimeException("secret-code")));
        var failure=assertThrows(IllegalStateException.class,()->new ReauthenticationRequestReader(validator).read(request("{\"method\":\"PASSWORD\",\"password\":\"secret-password\"}")));
        assertFalse(failure.getMessage().contains("secret"));assertFalse(failure.getCause().getMessage().contains("secret"));assertNull(failure.getCause().getCause());
    }
}

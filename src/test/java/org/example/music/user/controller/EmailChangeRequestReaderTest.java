package org.example.music.user.controller;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.MockHttpServletRequest;

class EmailChangeRequestReaderTest {
    static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    final EmailChangeRequestReader reader = new EmailChangeRequestReader(FACTORY.getValidator());
    @AfterAll static void close() { FACTORY.close(); }
    MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest(); request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8)); return request;
    }
    @Test void should_normalize_and_validate_length_after_trim() {
        assertEquals("new@example.com", reader.read(request("{\"newEmail\":\" New@Example.com \"}")).newEmail());
        String address = "a".repeat(64) + "@" + "b".repeat(51) + ".com";
        assertEquals(120, address.length());
        assertEquals(address, reader.read(request("{\"newEmail\":\" " + address + " \"}")).newEmail());
        assertThrows(EmailChangeValidationException.class, () -> reader.read(request("{\"newEmail\":\"" + address.replace(".com", "b.com") + "\"}")));
    }
    @ParameterizedTest @ValueSource(strings = {"", " ", "null", "{}", "[]", "true", "{", "{\"newEmail\":null}", "{\"newEmail\":1}", "{\"newEmail\":{}}", "{\"newEmail\":\"\"}", "{\"newEmail\":\"  \"}", "{\"newEmail\":\"a@b.com\",\"newEmail\":\"a@b.com\"}", "{\"newEmail\":\"a@b.com\",\"userId\":1}", "{\"newEmail\":\"a@b.com\"}{}", "{\"newEmail\":\"Alice <a@b.com>\"}", "{\"newEmail\":\"a@b.com,c@d.com\"}", "{\"newEmail\":\"a@b.com\\r\\n\"}"})
    void should_reject_invalid_bodies(String body) { assertThrows(EmailChangeValidationException.class, () -> reader.read(request(body))); }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"text/plain", "application/xml", "bad", "application/json;charset=UTF-16", "application/json;charset=unknown"})
    void should_return_dedicated_415_for_media_type(String type) {
        var input = request("{}"); input.setContentType(type);
        assertThrows(EmailChangeUnsupportedMediaTypeException.class, () -> reader.read(input));
    }
    @Test void should_enforce_byte_budget_including_trailing_whitespace_without_content_length() {
        String body = "{\"newEmail\":\"a@b.com\"}";
        assertEquals("a@b.com", reader.read(request(body + " ".repeat(16384 - body.length()))).newEmail());
        assertThrows(EmailChangeValidationException.class, () -> reader.read(request(body + " ".repeat(16385 - body.length()))));
    }
    @Test void should_reject_invalid_utf8_and_accept_utf8_media_type() {
        var input = request("{\"newEmail\":\"a@b.com\"}"); input.setContentType("Application/JSON;charset=UTF-8");
        assertEquals("a@b.com", reader.read(input).newEmail());
        input.setContent(new byte[]{(byte) 0xc3, 0x28});
        assertThrows(EmailChangeValidationException.class, () -> reader.read(input));
    }
}

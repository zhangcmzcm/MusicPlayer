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

class EmailChangeConfirmationRequestReaderTest {
    static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    static final String ID = "A".repeat(43);
    final EmailChangeConfirmationRequestReader reader = new EmailChangeConfirmationRequestReader(FACTORY.getValidator());
    @AfterAll static void close() { FACTORY.close(); }
    MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContentType("application/json"); request.setContent(body.getBytes(StandardCharsets.UTF_8)); return request;
    }
    String body(String id, String code) { return "{\"challengeId\":\"" + id + "\",\"code\":\"" + code + "\"}"; }
    @Test void should_accept_canonical_id_and_leading_zero_without_leaking_secrets() {
        var result = reader.read(request(body(ID,"012345")));
        assertEquals(ID,result.challengeId()); assertEquals("012345",result.code());
        assertFalse(result.toString().contains(ID)); assertFalse(result.toString().contains("012345"));
    }
    @ParameterizedTest @ValueSource(strings={"", "A", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB", " AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void should_reject_noncanonical_identifiers(String id) { assertThrows(EmailChangeValidationException.class,()->reader.read(request(body(id,"012345")))); }
    @ParameterizedTest @ValueSource(strings={"", "12345", "1234567", " 123456", "123456 ", "١٢٣٤٥٦", "12a456"})
    void should_reject_invalid_codes(String code) { assertThrows(EmailChangeValidationException.class,()->reader.read(request(body(ID,code)))); }
    @ParameterizedTest @ValueSource(strings={"", "null", "{}", "[]", "{", "{\"challengeId\":1,\"code\":\"012345\"}", "{\"code\":123456}", "{\"code\":null}", "{\"code\":{}}"})
    void should_reject_invalid_shapes(String body) { assertThrows(EmailChangeValidationException.class,()->reader.read(request(body))); }
    @Test void should_reject_unknown_duplicate_decoded_keys_and_trailing_json() {
        String valid=body(ID,"012345");
        for(String invalid:new String[]{valid+"{}",valid.replace("}",",\"newEmail\":\"a@example.com\"}"),valid.replace("}",",\"co\\u0064e\":\"012345\"}")})
            assertThrows(EmailChangeValidationException.class,()->reader.read(request(invalid)));
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"text/plain","bad","application/json;charset=UTF-16","application/json;charset=unknown"})
    void should_return_415_for_unsupported_media_type(String type) {
        var request=request(body(ID,"012345"));request.setContentType(type);
        assertThrows(EmailChangeUnsupportedMediaTypeException.class,()->reader.read(request));
    }
    @Test void should_enforce_utf8_and_full_body_budget() {
        String valid=body(ID,"012345");var request=request(valid+" ".repeat(16384-valid.length()));
        assertEquals("012345",reader.read(request).code());
        assertThrows(EmailChangeValidationException.class,()->reader.read(request(valid+" ".repeat(16385-valid.length()))));
        request.setContent(new byte[]{(byte)0xc3,0x28});assertThrows(EmailChangeValidationException.class,()->reader.read(request));
    }
}

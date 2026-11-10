package org.example.music.user.controller;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class PasswordResetConfirmationRequestReaderTest {
    static final jakarta.validation.ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    final PasswordResetConfirmationRequestReader reader = new PasswordResetConfirmationRequestReader(FACTORY.getValidator());
    final String id = "A".repeat(43);
    @AfterAll static void close() { FACTORY.close(); }
    MockHttpServletRequest request(String body) {
        var input = new MockHttpServletRequest();
        input.setContentType("application/json");
        input.setContent(body.getBytes(StandardCharsets.UTF_8));
        return input;
    }
    String body(String password) { return "{\"resetTicket\":\"" + id + "\",\"newPassword\":\"" + password + "\"}"; }

    @Test void should_preserve_ticket_and_password_spaces() {
        var result = reader.read(request(body("  new-pass  ")));
        assertEquals(id, result.resetTicket());
        assertEquals("  new-pass  ", result.newPassword());
        assertFalse(result.toString().contains("new-pass"));
        assertFalse(result.toString().contains(id));
    }
    @Test void should_accept_8_and_72_printable_ascii_and_exact_budget() {
        for (int size : new int[]{8,72}) assertEquals(size, reader.read(request(body("x".repeat(size)))).newPassword().length());
        String valid = body("new-pass");
        assertNotNull(reader.read(request(valid + " ".repeat(16384-valid.length()))));
        assertThrows(PasswordResetConfirmationValidationException.class, () -> reader.read(request(valid + " ".repeat(16385-valid.length()))));
    }
    @ParameterizedTest @ValueSource(strings={"", "short", "1234567", "        ", "中文密码中文密码", "new\\npassword", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void should_reject_invalid_password(String value) {
        assertThrows(PasswordResetConfirmationValidationException.class, () -> reader.read(request(body(value))));
    }
    @Test void should_reject_invalid_json_types_duplicates_and_legacy_fields() {
        String valid = body("new-pass");
        for (String invalid : new String[]{"{}", "[]", "null", valid+"{}", valid.replace("\""+id+"\"", "123456"),
                valid.replace(id,"A".repeat(42)+"B"), valid.replace("}",",\"email\":\"a@b.com\"}"),
                valid.replace("}",",\"resetTicket\":\""+id+"\"}"), valid.replace("}",",\"\\u0072esetTicket\":\""+id+"\"}"),
                valid.replace("\""+id+"\"","{}"), valid.replace("\""+id+"\"","null"), valid.replace("\""+id+"\"","[]"),
                valid.replace("\"new-pass\"","null"), valid.replace("new-pass","new\\tpass"),
                valid.replace("new-pass","new\\ud800pass"), valid.replace("new-pass","new\\u007fpass"),
                valid.replace("new-pass","new\\u001fpass"), valid.replace(id," "+id),
                valid.replace("newPassword","confirmPassword"), valid.replace("resetTicket","challengeId"),
                valid.replace("}",",\"code\":\"012345\"}"),
                "{\"challengeId\":\""+id+"\",\"code\":\"012345\",\"newPassword\":\"new-pass\"}"}) {
            assertThrows(PasswordResetConfirmationValidationException.class, () -> reader.read(request(invalid)), invalid);
        }
    }
    @Test void should_require_utf8_json_and_reject_malformed_utf8() {
        for (String type : new String[]{"text/plain","application/json;charset=UTF-16","bad"}) {
            var input=request(body("new-pass"));
            input.setContentType(type);
            assertThrows(PasswordResetConfirmationUnsupportedMediaTypeException.class, () -> reader.read(input));
        }
        var input=request("");
        input.setContent(new byte[]{(byte)0xc3,0x28});
        assertThrows(PasswordResetConfirmationValidationException.class, () -> reader.read(input));
        input.setContentType(null);
        assertThrows(PasswordResetConfirmationUnsupportedMediaTypeException.class, () -> reader.read(input));
    }
}

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
        input.setContentType("application/json"); input.setContent(body.getBytes(StandardCharsets.UTF_8)); return input;
    }
    String body(String password) { return "{\"challengeId\":\"" + id + "\",\"code\":\"012345\",\"newPassword\":\"" + password + "\"}"; }
    @Test void should_preserve_code_zero_and_password_spaces() {
        var result = reader.read(request(body("  new-pass  ")));
        assertEquals("012345", result.code()); assertEquals("  new-pass  ", result.newPassword());
        assertFalse(result.toString().contains("new-pass"));
    }
    @Test void should_accept_8_and_72_printable_ascii_and_exact_budget() {
        for (int size : new int[]{8,72}) assertEquals(size, reader.read(request(body("x".repeat(size)))).newPassword().length());
        String valid = body("new-pass");
        assertNotNull(reader.read(request(valid + " ".repeat(16384-valid.length()))));
        assertThrows(PasswordResetConfirmationValidationException.class, () -> reader.read(request(valid + " ".repeat(16385-valid.length()))));
    }
    @ParameterizedTest @ValueSource(strings={"", "short", "        ", "中文密码中文密码", "new\\npassword", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void should_reject_invalid_password(String value) {
        assertThrows(PasswordResetConfirmationValidationException.class, () -> reader.read(request(body(value))));
    }
    @Test void should_reject_invalid_json_types_duplicates_and_identifiers() {
        String valid = body("new-pass");
        for (String invalid : new String[]{"{}", "[]", "null", valid+"{}", valid.replace("012345","１２３４５６"),
                valid.replace("\"012345\"","123456"), valid.replace(id,"A".repeat(42)+"B"),
                valid.replace("}",",\"email\":\"a@b.com\"}"), valid.replace("}",",\"code\":\"012345\"}"),
                valid.replace("}",",\"\\u0063ode\":\"012345\"}"), valid.replace("\"012345\"","{}")}) {
            assertThrows(PasswordResetConfirmationValidationException.class, () -> reader.read(request(invalid)));
        }
    }
    @Test void should_require_utf8_json_and_reject_malformed_utf8() {
        for (String type : new String[]{"text/plain","application/json;charset=UTF-16","bad"}) {
            var input=request(body("new-pass")); input.setContentType(type);
            assertThrows(PasswordResetConfirmationUnsupportedMediaTypeException.class, () -> reader.read(input));
        }
        var input=request(""); input.setContent(new byte[]{(byte)0xc3,0x28});
        assertThrows(PasswordResetConfirmationValidationException.class, () -> reader.read(input));
    }
}

package org.example.music.user.controller;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class PasswordResetVerificationRequestReaderTest {
    static final jakarta.validation.ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    final PasswordResetVerificationRequestReader reader = new PasswordResetVerificationRequestReader(VALIDATORS.getValidator());
    final String id = "A".repeat(43);
    String body() { return "{\"challengeId\":\""+id+"\",\"code\":\"012345\"}"; }
    MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContentType("application/json"); request.setContent(body.getBytes(StandardCharsets.UTF_8)); return request;
    }
    @AfterAll static void close() { VALIDATORS.close(); }
    @Test void should_preserve_leading_zero_and_accept_both_field_orders() {
        var result = reader.read(request(body())); assertEquals("012345",result.code()); assertEquals(id,result.challengeId());
        assertEquals(result,reader.read(request("{\"code\":\"012345\",\"challengeId\":\""+id+"\"}")));
        assertFalse(result.toString().contains("012345"));
    }
    @Test void should_enforce_exact_byte_budget() {
        var body = body(); assertNotNull(reader.read(request(body+" ".repeat(16384-body.length()))));
        assertThrows(PasswordResetConfirmationValidationException.class,()->reader.read(request(body+" ".repeat(16385-body.length()))));
    }
    @Test void should_reject_bad_fields_types_duplicates_and_noncanonical_identifiers() {
        var valid = body();
        for (String bad : new String[]{"", "{}", "null", "[]", "{", valid+"{}", valid+"false",
                valid.replace(id,"A".repeat(42)+"B"),valid.replace(id," "+id),valid.replace("012345","１２３４５６"),
                valid.replace("\"012345\"","123456"),valid.replace("\"012345\"","null"),valid.replace("\"012345\"","[]"),
                valid.replace("\"012345\"","{}"),valid.replace("012345","01234"),valid.replace("012345","0123456"),
                valid.replace("012345","01234\\ud800"),valid.replace("}",",\"code\":\"012345\"}"),
                valid.replace("}",",\"\\u0063ode\":\"012345\"}")}) {
            assertThrows(PasswordResetConfirmationValidationException.class,()->reader.read(request(bad)));
        }
        for (String name : new String[]{"newPassword","email","userId","role","resetTicket"}) {
            assertThrows(PasswordResetConfirmationValidationException.class,()->reader.read(request(valid.replace("}",",\""+name+"\":\"extra\"}"))));
        }
    }
    @ParameterizedTest @ValueSource(strings={"text/plain","application/json;charset=UTF-16","bad","application/json;charset=unknown"})
    void should_reject_unsupported_media_type(String type) {
        var input = request(body()); input.setContentType(type);
        assertThrows(PasswordResetConfirmationUnsupportedMediaTypeException.class,()->reader.read(input));
    }
    @Test void should_require_media_type_and_valid_utf8() {
        var input = request(body()); input.setContentType(null);
        assertThrows(PasswordResetConfirmationUnsupportedMediaTypeException.class,()->reader.read(input));
        input.setContentType("application/json;charset=UTF-8"); input.setContent(new byte[]{(byte)0xc3,0x28});
        assertThrows(PasswordResetConfirmationValidationException.class,()->reader.read(input));
    }
}

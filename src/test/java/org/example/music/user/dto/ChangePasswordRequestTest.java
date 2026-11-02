package org.example.music.user.dto;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import java.util.Map;
import org.example.music.user.domain.UserPasswordSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class ChangePasswordRequestTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "true", "123", "\"text\"", "{}",
            "{\"oldPassword\":\"old\"}", "{\"oldPassword\":null,\"newPassword\":\"new\"}",
            "{\"oldPassword\":12,\"newPassword\":\"new\"}",
            "{\"oldPassword\":true,\"newPassword\":\"new\"}",
            "{\"oldPassword\":[],\"newPassword\":\"new\"}",
            "{\"oldPassword\":{},\"newPassword\":\"new\"}",
            "{\"oldPassword\":\"old\",\"newPassword\":null}",
            "{\"oldPassword\":\"old\",\"newPassword\":42}",
            "{\"oldPassword\":\"old\",\"newPassword\":\"new\",\"id\":null}",
            "{\"oldPassword\":\"old\",\"oldPassword\":\"old\",\"newPassword\":\"new\"}",
            "{\"oldPassword\":\"old\",\"newPassword\":\"new\",\"new\\u0050assword\":\"new\"}",
            "{\"oldPassword\":\"old\",\"newPassword\":\"new\"} {}",
            "{\"oldPassword\":\"old\",\"newPassword\":\"new\""})
    void should_reject_invalid_password_document(String document) {
        assertThrows(JacksonException.class, () -> json.readValue(document, ChangePasswordRequest.class));
    }

    @Test
    void should_accept_exactly_two_string_password_fields_in_any_order() {
        for (String document : new String[]{
                "{\"oldPassword\":\" 历史密码 \",\"newPassword\":\" new-password \"}",
                "{\"newPassword\":\" new-password \",\"oldPassword\":\" 历史密码 \"}"}) {
            var request = json.readValue(document, ChangePasswordRequest.class);
            assertEquals(" 历史密码 ", request.oldPassword());
            assertEquals(" new-password ", request.newPassword());
        }
    }

    @Test
    void should_validate_old_and_new_password_with_different_policies() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String old : new String[]{"a", "a".repeat(7), "a".repeat(8), "汉".repeat(128)}) {
                assertTrue(validator.validate(new ChangePasswordRequest(old, "new-pass")).isEmpty());
            }
            for (String old : new String[]{null, "", " \t\n", "a".repeat(129)}) {
                assertFalse(validator.validate(new ChangePasswordRequest(old, "new-pass")).isEmpty());
            }
            for (String password : new String[]{"a".repeat(8), "a".repeat(71), "a".repeat(72), " abcdef~ "}) {
                assertTrue(validator.validate(new ChangePasswordRequest("old", password)).isEmpty());
            }
            for (String password : new String[]{null, "", " ".repeat(72), "a".repeat(7), "a".repeat(73),
                    "abcdefgh汉", "abcdefgh😀", "abcdefgh👩‍💻", "汉".repeat(24), "abcdefgh\u00a0",
                    "abcdefgh\u007f", "abcdefgh\u0000", "abcdefgh\t", "abcdefgh\r", "abcdefgh\n"}) {
                assertFalse(validator.validate(new ChangePasswordRequest("old", password)).isEmpty());
            }
        }
    }

    @Test
    void should_redact_password_objects() {
        assertFalse(new ChangePasswordRequest("private-old", "private-new").toString().contains("private"));
        assertFalse(new UserPasswordSnapshot(42, "private-hash", 0).toString().contains("private"));
        String encoded = json.writeValueAsString(new ChangePasswordRequest("private-old", "private-new"));
        assertEquals(Map.of(), json.readValue(encoded, Map.class));
    }
}

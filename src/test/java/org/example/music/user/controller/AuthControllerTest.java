package org.example.music.user.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import org.mockito.ArgumentCaptor;
import org.example.music.config.SecurityConfig;
import org.example.music.user.service.UserAuthorizationStateService;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.UserRegisterRequest;
import org.example.music.user.dto.UserRegisterResponse;
import org.example.music.common.web.ClientIpResolver;
import org.example.music.user.dto.LoginRequest;
import org.example.music.user.dto.LoginResponse;
import org.example.music.user.service.UserLoginService;
import org.example.music.user.service.UserRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static Stream<String> invalidPasswords() {
        return Stream.of(null, "", " ".repeat(8), " ".repeat(72), "a".repeat(7), "a".repeat(73), "a".repeat(128),
                "密码测试abcdefgh", "abcdefgh😀", "abcdefgh👩‍💻", "abcdefghé", "abcdefgh！",
                "abcdefgh\u00a0", "abcdefgh\u007f", "abcdefgh\u0000", "abcdefgh\t",
                "abcdefgh\r", "abcdefgh\n", "汉".repeat(24));
    }

    static Stream<String> validPasswords() {
        String symbols = java.util.stream.IntStream.rangeClosed(33, 126)
                .filter(value -> !Character.isLetterOrDigit(value))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return Stream.of("a".repeat(8), "a".repeat(71), "a".repeat(72), "Abc12345", symbols,
                " abcdefgh ", "abcdefgh~", "12345678");
    }

    @ParameterizedTest
    @MethodSource("invalidPasswords")
    void should_reject_registration_password_outside_ascii_policy(String password) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("username", "alice_2026");
        request.put("password", password);
        request.put("email", "alice@example.com");
        request.put("code", "123456");
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.type").value("urn:problem:music:validation_failed"))
                .andExpect(jsonPath("$.errors.password").exists());
        verifyNoInteractions(userRegistrationService);
    }

    @Test
    void should_reject_missing_registration_password() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice_2026\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(userRegistrationService);
    }

    @ParameterizedTest
    @MethodSource("validPasswords")
    void should_preserve_valid_registration_password(String password) throws Exception {
        when(userRegistrationService.register(any(UserRegisterRequest.class))).thenReturn(
                new UserRegisterResponse(42, "alice_2026", null, UserRole.USER, Instant.EPOCH));
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("username", "alice_2026", "password", password))))
                .andExpect(status().isCreated());
        ArgumentCaptor<UserRegisterRequest> captor = ArgumentCaptor.forClass(UserRegisterRequest.class);
        verify(userRegistrationService).register(captor.capture());
        assertEquals(password, captor.getValue().password());
    }

    @Test
    void should_not_echo_rejected_password() throws Exception {
        String password = "unique-secret-禁止-9382";
        String response = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("username", "alice_2026", "password", password))))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(password));
        assertFalse(response.contains(JSON.writeValueAsString(password)));
        verifyNoInteractions(userRegistrationService);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserAuthorizationStateService userAuthorizationStateService;

    @MockitoBean
    private UserRegistrationService userRegistrationService;

    @MockitoBean
    private UserLoginService userLoginService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @Test
    void should_register_user_without_authentication_when_request_is_valid() throws Exception {
        when(userRegistrationService.register(any(UserRegisterRequest.class))).thenReturn(
                new UserRegisterResponse(
                        42,
                        "alice_2026",
                        null,
                        UserRole.USER,
                        Instant.parse("2026-10-21T12:34:56Z")
                )
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "alice_2026",
                                  "password": "correct-password"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.username").value("alice_2026"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.userRole").value("USER"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-21T12:34:56Z"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordToken").doesNotExist())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void should_return_400_problem_when_email_has_no_verification_code() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "alice_2026",
                                  "password": "correct-password",
                                  "email": "alice@example.com"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.emailCodePairValid").exists());

        verifyNoInteractions(userRegistrationService);
    }

    @Test
    void should_return_400_problem_when_request_body_is_missing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(userRegistrationService);
    }

    @Test
    void should_reject_username_outside_configured_boundary() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "ab",
                                  "password": "correct-password"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.username").exists());

        verifyNoInteractions(userRegistrationService);
    }

    @Test
    void should_login_without_authentication_when_request_is_valid() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("198.51.100.10");
        when(userLoginService.login(any(LoginRequest.class), eq("198.51.100.10")))
                .thenReturn(LoginResponse.authenticated(null));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "loginType": "USERNAME_PASSWORD",
                                  "account": "alice",
                                  "password": "correct-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"));

        verify(userLoginService).login(any(LoginRequest.class), eq("198.51.100.10"));
    }

    @Test
    void should_return_400_problem_when_login_fields_do_not_match_type() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "loginType": "EMAIL_CODE",
                                  "email": "alice@example.com"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.loginFieldsValid").exists());

        verifyNoInteractions(userLoginService);
    }
}

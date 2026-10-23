package org.example.music.user.controller;

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
import org.example.music.config.SecurityConfig;
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

    @Autowired
    private MockMvc mockMvc;

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
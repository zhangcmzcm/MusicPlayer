package org.example.music.email.controller;

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
import org.example.music.common.web.ClientIpResolver;
import org.example.music.config.SecurityConfig;
import org.example.music.user.service.UserAuthorizationStateService;
import org.example.music.email.dto.EmailSendCodeRequest;
import org.example.music.email.dto.EmailSendCodeResponse;
import org.example.music.email.service.EmailSendCodeService;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EmailController.class)
@Import(SecurityConfig.class)
class EmailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserAuthorizationStateService userAuthorizationStateService;

    @MockitoBean
    private EmailSendCodeService emailSendCodeService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @Test
    void should_send_code_without_authentication_when_request_is_valid() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("198.51.100.10");
        when(emailSendCodeService.send(any(EmailSendCodeRequest.class), eq("198.51.100.10")))
                .thenReturn(new EmailSendCodeResponse(
                        VerificationCodePurpose.REGISTER,
                        Instant.parse("2026-10-22T12:40:00Z")
                ));

        mockMvc.perform(post("/api/v1/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "alice@example.com",
                                  "purpose": "REGISTER"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.purpose").value("REGISTER"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-22T12:40:00Z"))
                .andExpect(jsonPath("$.code").doesNotExist());

        verify(emailSendCodeService)
                .send(any(EmailSendCodeRequest.class), eq("198.51.100.10"));
    }

    @Test
    void should_return_400_problem_when_email_is_invalid() throws Exception {
        mockMvc.perform(post("/api/v1/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "not-an-email",
                                  "purpose": "REGISTER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.email").exists());

        verifyNoInteractions(emailSendCodeService);
    }

    @Test
    void should_return_400_problem_when_purpose_is_missing() throws Exception {
        mockMvc.perform(post("/api/v1/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "alice@example.com"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(emailSendCodeService);
    }

    @Test
    void should_return_400_problem_when_request_body_is_missing() throws Exception {
        mockMvc.perform(post("/api/v1/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(emailSendCodeService);
    }
}
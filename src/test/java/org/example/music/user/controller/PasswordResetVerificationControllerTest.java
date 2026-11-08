package org.example.music.user.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import org.example.music.common.web.ClientIpResolver;
import org.example.music.config.SecurityConfig;
import org.example.music.user.dto.*;
import org.example.music.user.exception.*;
import org.example.music.user.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PasswordResetVerificationController.class)
@Import({SecurityConfig.class,ClientIpResolver.class,PasswordResetVerificationRequestReader.class}) @ActiveProfiles("test")
class PasswordResetVerificationControllerTest {
    static final String PATH="/api/v1/users/password-reset-verifications",BODY="{\"challengeId\":\""+"A".repeat(43)+"\",\"code\":\"012345\"}";
    @Autowired MockMvc mvc;
    @MockitoBean PasswordResetVerificationService service;
    @MockitoBean UserAuthorizationStateService authorizationState;
    @BeforeEach void setup() { when(service.verify(any(),anyString())).thenReturn(new PasswordResetVerificationResponse("A".repeat(43),Instant.parse("2026-11-08T00:05:00Z"))); }
    @Test void should_allow_anonymous_return_two_fields_and_use_remote_ip() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(BODY).header("X-Forwarded-For","forged"))
                .andExpect(status().isOk()).andExpect(jsonPath("$",aMapWithSize(2))).andExpect(jsonPath("$.resetTicket").value("A".repeat(43)))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-08T00:05:00Z")).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().doesNotExist("Location"));
        verify(service).verify(eq(new PasswordResetVerificationRequest("A".repeat(43),"012345")),eq("127.0.0.1"));
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
    @Test void should_reject_invalid_body_media_type_and_bearer_before_service() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(PATH).contentType("text/plain").content(BODY)).andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(post(PATH).header("Authorization","Bearer invalid.jwt.signature").contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void should_map_verification_errors_without_secret_or_account_wait_fields() throws Exception {
        RuntimeException[] errors={new PasswordResetVerificationFailedException(),new PasswordResetVerificationRateLimitedException(57),new PasswordResetVerificationUnavailableException(new RuntimeException("private-ticket")),new IllegalStateException("private-ticket")};
        String[] codes={"PASSWORD_RESET_VERIFICATION_FAILED","PASSWORD_RESET_VERIFICATION_RATE_LIMITED","PASSWORD_RESET_VERIFICATION_UNAVAILABLE","INTERNAL_ERROR"};int[] statuses={400,429,503,500};
        for(int i=0;i<errors.length;i++) {
            doThrow(errors[i]).when(service).verify(any(),anyString());
            var result=mvc.perform(post(PATH).contentType("application/json").content(BODY)).andExpect(status().is(statuses[i]))
                    .andExpect(jsonPath("$.code").value(codes[i])).andExpect(jsonPath("$.type").value("urn:problem:music:"+codes[i].toLowerCase(java.util.Locale.ROOT)))
                    .andExpect(header().string("Cache-Control","no-store")).andExpect(content().string(not(containsString("private-ticket"))));
            if(i==1) result.andExpect(header().string("Retry-After","57"));
            else result.andExpect(header().doesNotExist("Retry-After"));
            if(i==0) result.andExpect(jsonPath("$.title").value("密码重置未完成")).andExpect(jsonPath("$.detail").value("验证码或找回申请无效，请检查或重新申请"));
        }
    }
}

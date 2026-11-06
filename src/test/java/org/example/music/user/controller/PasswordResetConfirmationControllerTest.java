package org.example.music.user.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.example.music.common.web.ClientIpResolver;
import org.example.music.config.SecurityConfig;
import org.example.music.user.dto.PasswordResetConfirmationRequest;
import org.example.music.user.exception.*;
import org.example.music.user.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PasswordResetConfirmationController.class)
@Import({SecurityConfig.class,ClientIpResolver.class,PasswordResetConfirmationRequestReader.class}) @ActiveProfiles("test")
class PasswordResetConfirmationControllerTest {
    static final String PATH="/api/v1/users/password-resets",BODY="{\"challengeId\":\""+"A".repeat(43)+"\",\"code\":\"012345\",\"newPassword\":\" new-pass \"}";
    @Autowired MockMvc mvc;
    @MockitoBean PasswordResetConfirmationService service;
    @MockitoBean UserAuthorizationStateService authorizationState;
    @Test void should_allow_anonymous_and_use_remote_ip() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(BODY).header("X-Forwarded-For","forged"))
                .andExpect(status().isNoContent()).andExpect(content().string("")).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().doesNotExist("Location"));
        verify(service).reset(eq(new PasswordResetConfirmationRequest("A".repeat(43),"012345"," new-pass ")),eq("127.0.0.1"));
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
    @Test void should_reject_invalid_request_before_service() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(PATH).contentType("text/plain").content(BODY)).andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        verifyNoInteractions(service);
    }
    @Test void should_map_uniform_failure_and_storage_faults_without_secret() throws Exception {
        RuntimeException[] errors={new PasswordResetVerificationFailedException(),new NewPasswordSameAsOldException(),new PasswordResetConfirmationRateLimitedException(60),new PasswordResetConfirmationUnavailableException(new RuntimeException("secret")),new IllegalStateException("secret")};
        String[] codes={"PASSWORD_RESET_VERIFICATION_FAILED","NEW_PASSWORD_SAME_AS_OLD","PASSWORD_RESET_CONFIRMATION_RATE_LIMITED","PASSWORD_RESET_CONFIRMATION_UNAVAILABLE","INTERNAL_ERROR"};
        int[] statuses={400,400,429,503,500};
        for(int i=0;i<errors.length;i++) {
            doThrow(errors[i]).when(service).reset(any(),anyString());
            var result=mvc.perform(post(PATH).contentType("application/json").content(BODY)).andExpect(status().is(statuses[i]))
                    .andExpect(jsonPath("$.code").value(codes[i])).andExpect(jsonPath("$.type").value("urn:problem:music:"+codes[i].toLowerCase(java.util.Locale.ROOT)))
                    .andExpect(content().string(not(containsString("secret")))).andExpect(header().string("Cache-Control","no-store"));
            if(i==2) result.andExpect(header().string("Retry-After","60"));
            else result.andExpect(header().doesNotExist("Retry-After"));
            if(i==0) result.andExpect(jsonPath("$.title").value("密码重置未完成")).andExpect(jsonPath("$.detail").value("验证码或找回申请无效，请检查或重新申请"));
        }
    }
}

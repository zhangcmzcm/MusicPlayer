package org.example.music.user.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Stream;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.config.SecurityConfig;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.ChangePasswordRequest;
import org.example.music.user.exception.*;
import org.example.music.user.service.CurrentUserPasswordService;
import org.example.music.user.service.UserAuthorizationStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CurrentUserPasswordController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class CurrentUserPasswordControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private CurrentUserPasswordService service;
    @MockitoBean private UserAuthorizationStateService states;
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(42, UserRole.USER);
    private final ChangePasswordRequest request = new ChangePasswordRequest("old-pass", "new-pass");
    private static final String BODY = "{\"oldPassword\":\"old-pass\",\"newPassword\":\"new-pass\"}";

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(actor, null, List.of());
    }

    @Test
    void should_return_empty_no_store_and_use_only_authenticated_actor() throws Exception {
        mvc.perform(put("/api/v1/users/me/password").with(authentication(auth()))
                        .param("userId", "99").header("X-User-Id", "99")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(service).changePassword(actor, request);
    }

    @Test
    void should_require_authentication() throws Exception {
        mvc.perform(put("/api/v1/users/me/password").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "[]", "{\"oldPassword\":\"old-pass\"}",
            "{\"oldPassword\":\"old-pass\",\"newPassword\":12}",
            "{\"oldPassword\":\"old-pass\",\"newPassword\":\"new-pass\",\"userId\":99}",
            "{\"oldPassword\":\"old-pass\",\"newPassword\":\"new-pass\",\"newPassword\":\"new-pass\"}",
            "{\"oldPassword\":\"old-pass\",\"newPassword\":\"中文密码测试用例\"}",
            "{\"oldPassword\":\"old-pass\",\"newPassword\":\"short\"}"})
    void should_reject_invalid_document_without_calling_service(String body) throws Exception {
        mvc.perform(put("/api/v1/users/me/password").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        verifyNoInteractions(service);
    }

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(new OldPasswordIncorrectException(), 400, "OLD_PASSWORD_INCORRECT"),
                Arguments.of(new NewPasswordSameAsOldException(), 400, "NEW_PASSWORD_SAME_AS_OLD"),
                Arguments.of(new PasswordChangeConflictException(), 409, "PASSWORD_CHANGE_CONFLICT"),
                Arguments.of(new PasswordChangeRateLimitedException(899), 429, "PASSWORD_CHANGE_RATE_LIMITED"),
                Arguments.of(new PasswordChangeRateLimitUnavailableException(new IllegalStateException("private-secret")), 503, "PASSWORD_CHANGE_RATE_LIMIT_UNAVAILABLE"),
                Arguments.of(new PasswordChangeStoreUnavailableException(new IllegalStateException("private-secret")), 503, "PASSWORD_CHANGE_STORE_UNAVAILABLE"),
                Arguments.of(new CurrentUserRevokedException(), 401, "TOKEN_REVOKED"),
                Arguments.of(new IllegalStateException("private-secret"), 500, "INTERNAL_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("errors")
    void should_preserve_problem_contract(RuntimeException failure, int statusCode, String code) throws Exception {
        doThrow(failure).when(service).changePassword(actor, request);
        var response = mvc.perform(put("/api/v1/users/me/password").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(statusCode))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type").value("urn:problem:music:" + code.toLowerCase()))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        if (statusCode == 429) response.andExpect(header().string("Retry-After", "899"));
        assertFalse(response.andReturn().getResponse().getContentAsString().contains("private-secret"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void should_redact_storage_failure_logs_including_nested_causes(boolean rateLimit) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var cause = new IllegalStateException("private-secret", new RuntimeException("private-secret"));
            cause.addSuppressed(new RuntimeException("private-secret"));
            doThrow(rateLimit ? new PasswordChangeRateLimitUnavailableException(cause)
                    : new PasswordChangeStoreUnavailableException(cause)).when(service).changePassword(actor, request);
            mvc.perform(put("/api/v1/users/me/password").with(authentication(auth()))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isServiceUnavailable());
            assertEquals(1, appender.list.size());
            var event = appender.list.getFirst();
            assertTrue(event.getFormattedMessage().contains("exceptionType="));
            assertFalse(event.getFormattedMessage().contains("private-secret"));
            assertNotNull(event.getThrowableProxy());
            assertFalse(event.getThrowableProxy().getMessage().contains("private-secret"));
            assertNull(event.getThrowableProxy().getCause());
            assertEquals(0, event.getThrowableProxy().getSuppressed().length);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}

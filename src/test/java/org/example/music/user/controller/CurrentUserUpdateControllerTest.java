package org.example.music.user.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.example.music.config.SecurityConfig;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.CurrentUserResponse;
import org.example.music.user.dto.UpdateCurrentUserRequest;
import org.example.music.user.exception.CurrentUserRevokedException;
import org.example.music.user.exception.UserAlreadyExistsException;
import org.example.music.user.exception.UserProfileUpdateUnavailableException;
import org.example.music.user.service.CurrentUserUpdateService;
import org.example.music.user.service.UserAuthorizationStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CurrentUserUpdateController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class CurrentUserUpdateControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private CurrentUserUpdateService service;
    @MockitoBean private UserAuthorizationStateService authorizationState;
    // 主体注入仅测试 HTTP 契约；真实签名和主体转换由集成测试证明。
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(42, UserRole.USER);

    @ParameterizedTest
    @ValueSource(strings = {"abc", "abcdefghijklmnopqrst", "123", "___", "Alice_new"})
    void should_accept_valid_username_and_preserve_public_contract(String username) throws Exception {
        when(service.updateCurrentUser(actor, new UpdateCurrentUserRequest(username)))
                .thenReturn(new CurrentUserResponse(42, username, "alice@example.com", UserRole.ADMIN,
                        UserRole.USER, Instant.parse("2026-10-21T12:34:56.123456Z")));
        mvc.perform(patch("/api/v1/users/me").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$.id").value(42)).andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.userRole").value("ADMIN"))
                .andExpect(jsonPath("$.loginRole").value("USER"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-21T12:34:56.123456Z"));
        verify(service).updateCurrentUser(actor, new UpdateCurrentUserRequest(username));
    }

    static Stream<String> invalidBodies() {
        return Stream.of("", "null", "[]", "\"alice\"", "123", "true", "{}", "{",
                "{\"username\":null}", "{\"username\":123}", "{\"username\":false}",
                "{\"username\":[]}", "{\"username\":{}}", "{\"Username\":\"alice\"}",
                "{\"username\":\"\"}", "{\"username\":\"ab\"}",
                "{\"username\":\"abcdefghijklmnopqrstu\"}", "{\"username\":\" alice\"}",
                "{\"username\":\"alice \"}", "{\"username\":\"   \"}",
                "{\"username\":\"ali\\tce\"}", "{\"username\":\"ali\\nce\"}",
                "{\"username\":\"alice\\n\"}", "{\"username\":\"alice\\t\"}",
                "{\"username\":\"用户一\"}", "{\"username\":\"alice-1\"}",
                "{\"username\":\"alice\",\"username\":\"alice\"}",
                "{\"username\":\"alice\",\"user\\u006eame\":\"other\"}",
                "{\"username\":\"alice\"} {}",
                "{\"username\":\"alice\",\"email\":null}",
                "{\"username\":\"alice\",\"password\":\"secret\"}",
                "{\"username\":\"alice\",\"passwordHash\":null}",
                "{\"username\":\"alice\",\"role\":\"ADMIN\"}",
                "{\"username\":\"alice\",\"userRole\":null}",
                "{\"username\":\"alice\",\"loginRole\":null}",
                "{\"username\":\"alice\",\"userId\":43}",
                "{\"username\":\"alice\",\"id\":43}",
                "{\"username\":\"alice\",\"tokenVersion\":0}",
                "{\"username\":\"alice\",\"updatedAt\":null}",
                "{\"username\":\"alice\",\"unknown\":null}");
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void should_return_400_without_calling_service_for_invalid_input(String body) throws Exception {
        mvc.perform(patch("/api/v1/users/me").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.type").value("urn:problem:music:validation_failed"));
        verifyNoInteractions(service);
    }

    @Test
    void should_preserve_null_keys_and_ignore_identity_parameters_and_headers() throws Exception {
        when(service.updateCurrentUser(actor, new UpdateCurrentUserRequest("alice")))
                .thenReturn(new CurrentUserResponse(42, "alice", null, UserRole.USER, UserRole.USER, null));
        mvc.perform(patch("/api/v1/users/me").with(authentication(auth())).param("userId", "99", "100")
                        .param("role", "ADMIN").header("X-User-Id", "99")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"alice\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$", hasKey("email"))).andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("createdAt"))).andExpect(jsonPath("$.createdAt").value(nullValue()))
                .andExpect(jsonPath("$.id").value(42));
        verify(service).updateCurrentUser(actor, new UpdateCurrentUserRequest("alice"));
    }

    @Test
    void should_require_authentication_before_body_validation() throws Exception {
        mvc.perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    static Stream<Arguments> failures() {
        return Stream.of(Arguments.of(new CurrentUserRevokedException(), 401, "TOKEN_REVOKED"),
                Arguments.of(new UserAlreadyExistsException("USERNAME_ALREADY_EXISTS", "用户名已占用"),
                        409, "USERNAME_ALREADY_EXISTS"),
                Arguments.of(new UserProfileUpdateUnavailableException(new IllegalStateException("private details")),
                        503, "USER_PROFILE_UPDATE_UNAVAILABLE"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void should_preserve_error_contract_and_hide_internal_details(RuntimeException error, int status, String code)
            throws Exception {
        when(service.updateCurrentUser(eq(actor), any())).thenThrow(error);
        mvc.perform(patch("/api/v1/users/me").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"alice\"}"))
                .andExpect(status().is(status)).andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type").value("urn:problem:music:" + code.toLowerCase()))
                .andExpect(content().string(not(containsString("private details"))));
    }

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}

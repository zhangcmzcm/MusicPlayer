package org.example.music.user.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import org.example.music.config.SecurityConfig;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.CurrentUserResponse;
import org.example.music.user.exception.CurrentUserRevokedException;
import org.example.music.user.exception.UserProfileStoreUnavailableException;
import org.example.music.user.service.CurrentUserService;
import org.example.music.user.service.UserAuthorizationStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CurrentUserController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class CurrentUserControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private CurrentUserService service;
    @MockitoBean private UserAuthorizationStateService authorizationState;
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(42, UserRole.USER);

    @Test
    void should_return_exact_public_fields_for_regular_login_identity() throws Exception {
        when(service.getCurrentUser(actor)).thenReturn(new CurrentUserResponse(42, "alice", "alice@example.com",
                UserRole.ADMIN, UserRole.USER, Instant.parse("2026-10-21T12:34:56Z")));
        mvc.perform(get("/api/v1/users/me").with(authentication(auth())))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$.id").value(42)).andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.userRole").value("ADMIN"))
                .andExpect(jsonPath("$.loginRole").value("USER"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-21T12:34:56Z"));
        verify(service).getCurrentUser(actor);
    }

    @Test
    void should_keep_null_keys_and_ignore_forged_and_repeated_identity_parameters() throws Exception {
        when(service.getCurrentUser(actor)).thenReturn(new CurrentUserResponse(42, "alice", null,
                UserRole.USER, UserRole.USER, null));
        mvc.perform(get("/api/v1/users/me").param("userId", "99", "100")
                        .param("loginRole", "ADMIN", "ADMIN").with(authentication(auth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$", hasKey("email"))).andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("createdAt"))).andExpect(jsonPath("$.createdAt").value(nullValue()))
                .andExpect(jsonPath("$.id").value(42)).andExpect(jsonPath("$.loginRole").value("USER"));
        verify(service).getCurrentUser(actor);
    }

    @Test
    void should_require_authentication() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void should_return_revoked_problem_when_account_disappears_after_authentication() throws Exception {
        when(service.getCurrentUser(actor)).thenThrow(new CurrentUserRevokedException());
        mvc.perform(get("/api/v1/users/me").with(authentication(auth())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"))
                .andExpect(jsonPath("$.type").value("urn:problem:music:token_revoked"));
    }

    @Test
    void should_return_store_problem_without_underlying_details() throws Exception {
        when(service.getCurrentUser(actor)).thenThrow(new UserProfileStoreUnavailableException(
                new IllegalStateException("private database details")));
        mvc.perform(get("/api/v1/users/me").with(authentication(auth())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.code").value("USER_PROFILE_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.type").value("urn:problem:music:user_profile_store_unavailable"))
                .andExpect(content().string(not(containsString("private database details"))));
    }

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}

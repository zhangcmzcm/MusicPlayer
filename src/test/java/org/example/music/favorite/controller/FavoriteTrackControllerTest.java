package org.example.music.favorite.controller;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.config.SecurityConfig;
import org.example.music.favorite.exception.FavoriteStoreUnavailableException;
import org.example.music.favorite.service.FavoriteTrackService;
import org.example.music.favorite.service.UnfavoriteTrackService;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.user.domain.UserRole;
import org.example.music.user.service.UserAuthorizationStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(FavoriteTrackController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class FavoriteTrackControllerTest {
    private static final String PATH = "/api/v1/users/me/favorites/";
    @Autowired MockMvc mvc;
    @MockitoBean FavoriteTrackService service;
    @MockitoBean UnfavoriteTrackService unfavoriteService;
    @MockitoBean UserAuthorizationStateService authorizationState;
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(7, UserRole.USER);

    private RequestPostProcessor user() {
        return authentication(new UsernamePasswordAuthenticationToken(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 42, Long.MAX_VALUE})
    void should_return_no_content_after_favorite(long id) throws Exception {
        mvc.perform(put(PATH + id).with(user()))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(service).favorite(id, actor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    void should_reject_invalid_id(String id) throws Exception {
        mvc.perform(put(PATH + id).with(user())).andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test
    void should_authenticate_before_validating_id() throws Exception {
        mvc.perform(put(PATH + "invalid")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void should_ignore_untrusted_identity_inputs() throws Exception {
        mvc.perform(put(PATH + "42").with(user()).param("userId", "999").param("role", "ADMIN")
                        .header("X-User-Id", "999").contentType("application/json")
                        .content("{\"userId\":999,\"role\":\"ADMIN\"}"))
                .andExpect(status().isNoContent());
        verify(service).favorite(42L, actor);
    }

    @Test
    void should_return_not_found() throws Exception {
        doThrow(new TrackNotFoundException(42L)).when(service).favorite(42L, actor);
        mvc.perform(put(PATH + "42").with(user())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
    }

    @Test
    void should_hide_storage_failure_details() throws Exception {
        doThrow(new FavoriteStoreUnavailableException(new RuntimeException("private SQL")))
                .when(service).favorite(42L, actor);
        mvc.perform(put(PATH + "42").with(user())).andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("FAVORITE_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.type").value("urn:problem:music:favorite_store_unavailable"))
                .andExpect(jsonPath("$.detail").value("歌曲收藏暂时无法完成"));
    }

    @Test
    void should_hide_unexpected_failure_details() throws Exception {
        doThrow(new IllegalStateException("private SQL")).when(service).favorite(42L, actor);
        mvc.perform(put(PATH + "42").with(user())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("The request could not be completed"));
    }
}

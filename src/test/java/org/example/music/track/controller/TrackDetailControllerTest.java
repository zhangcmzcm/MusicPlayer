package org.example.music.track.controller;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.List;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.config.SecurityConfig;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.dto.TrackDetailResponse;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.track.service.TrackDetailService;
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

@WebMvcTest(TrackDetailController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class TrackDetailControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private TrackDetailService service;
    @MockitoBean
    private UserAuthorizationStateService authorizationState;
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(7, UserRole.USER);

    private RequestPostProcessor user() {
        return authentication(new UsernamePasswordAuthenticationToken(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void should_return_detail_with_explicit_nulls_and_no_store() throws Exception {
        when(service.getDetail(42L, actor)).thenReturn(new TrackDetailResponse(42L, "标题", "艺术家",
                null, null, null, null, "PUBLISHED", null));
        mvc.perform(get("/api/v1/tracks/42").with(user()))
                .andExpect(status().isOk()).andExpect(content().contentType("application/json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("""
                    {"id":42,"title":"标题","artistName":"艺术家","albumName":null,"durationMs":null,
                     "trackNumber":null,"discNumber":null,"status":"PUBLISHED","cover":null}
                    """, org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808", "1.5"})
    void should_reject_invalid_ids(String id) throws Exception {
        mvc.perform(get("/api/v1/tracks/" + id).with(user())).andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test
    void should_require_authentication() throws Exception {
        mvc.perform(get("/api/v1/tracks/42")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void should_return_uniform_not_found() throws Exception {
        when(service.getDetail(42L, actor)).thenThrow(new TrackNotFoundException(42L));
        mvc.perform(get("/api/v1/tracks/42").with(user())).andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
    }

    @Test
    void should_return_internal_error_for_inconsistent_asset() throws Exception {
        when(service.getDetail(42L, actor)).thenThrow(new IllegalStateException("private details"));
        mvc.perform(get("/api/v1/tracks/42").with(user())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("The request could not be completed"));
    }

    @Test
    void should_return_store_unavailable() throws Exception {
        when(service.getDetail(42L, actor)).thenThrow(new TrackStoreUnavailableException("read failed", new RuntimeException()));
        mvc.perform(get("/api/v1/tracks/42").with(user())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRACK_STORE_UNAVAILABLE"));
    }
}

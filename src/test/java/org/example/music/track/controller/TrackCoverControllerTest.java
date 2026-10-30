package org.example.music.track.controller;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.config.SecurityConfig;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.storage.domain.AudioObjectRange;
import org.example.music.track.exception.TrackCoverNotFoundException;
import org.example.music.track.exception.TrackCoverStorageInconsistentException;
import org.example.music.track.service.PreparedTrackCover;
import org.example.music.track.service.TrackCoverService;
import org.example.music.track.service.TrackCoverSnapshot;
import org.example.music.track.web.TrackCoverErrorAdvice;
import org.example.music.track.web.TrackCoverResponseWriter;
import org.example.music.user.domain.UserRole;
import org.example.music.user.service.UserAuthorizationStateService;
import org.junit.jupiter.api.BeforeEach;
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

@WebMvcTest(TrackCoverController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TrackCoverErrorAdvice.class, TrackCoverResponseWriter.class})
class TrackCoverControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private TrackCoverService service;
    @MockitoBean private UserAuthorizationStateService authorization;
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(7, UserRole.USER);
    private final PreparedTrackCover cover = new PreparedTrackCover(new TrackCoverSnapshot(42L, "private", "secret", "image/png", 4, "a".repeat(64)), "\"" + "a".repeat(64) + "\"");

    private RequestPostProcessor user() {
        return authentication(new UsernamePasswordAuthenticationToken(actor, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @BeforeEach
    void setup() {
        when(service.prepare(42L, actor)).thenReturn(cover);
        when(service.open(cover)).thenAnswer(i -> new AudioObjectRange(new ByteArrayInputStream(new byte[]{1,2,3,4}), "image/png", 4, 0, 3, 4, "a".repeat(64)));
    }

    @Test
    void should_stream_complete_image_and_ignore_range() throws Exception {
        mvc.perform(get("/api/v1/tracks/42/cover").with(user()).header("Range", "bytes=1-2"))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1,2,3,4}))
                .andExpect(content().contentType("image/png")).andExpect(header().string("Content-Length", "4"))
                .andExpect(header().string("ETag", cover.etag())).andExpect(header().string("Cache-Control", "private, no-cache"))
                .andExpect(header().string("Vary", org.hamcrest.Matchers.containsString("Authorization")))
                .andExpect(header().doesNotExist("Content-Range")).andExpect(header().doesNotExist("Accept-Ranges"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"match", "weak", "list", "star"})
    void should_return_304_without_opening_stream(String condition) throws Exception {
        String value = switch (condition) {
            case "weak" -> "W/" + cover.etag();
            case "list" -> "\"other\", " + cover.etag();
            case "star" -> "*";
            default -> cover.etag();
        };
        mvc.perform(get("/api/v1/tracks/42/cover").with(user()).header("If-None-Match", value).header("Range", "bytes=1-2"))
                .andExpect(status().isNotModified()).andExpect(content().string(""))
                .andExpect(header().string("ETag", cover.etag())).andExpect(header().string("Cache-Control", "private, no-cache"))
                .andExpect(header().doesNotExist("Content-Length"));
        verify(service, never()).open(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    void should_reject_invalid_ids(String id) throws Exception {
        mvc.perform(get("/api/v1/tracks/" + id + "/cover").with(user()))
                .andExpect(status().isBadRequest()).andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(service);
    }

    @Test
    void should_require_authentication() throws Exception {
        mvc.perform(get("/api/v1/tracks/42/cover")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void should_not_return_304_for_missing_cover_or_invalid_storage() throws Exception {
        when(service.prepare(42L, actor)).thenThrow(new TrackCoverNotFoundException());
        mvc.perform(get("/api/v1/tracks/42/cover").with(user()).header("If-None-Match", "*"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COVER_NOT_FOUND"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("ETag"));
        doThrow(new TrackCoverStorageInconsistentException()).when(service).prepare(42L, actor);
        mvc.perform(get("/api/v1/tracks/42/cover").with(user()).header("If-None-Match", "*"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("COVER_STORAGE_INCONSISTENT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/webp"})
    void should_use_actual_validated_mime(String type) throws Exception {
        var other = new PreparedTrackCover(new TrackCoverSnapshot(42L, "private", "secret", type, 4, "a".repeat(64)), cover.etag());
        when(service.prepare(42L, actor)).thenReturn(other);
        when(service.open(other)).thenReturn(new AudioObjectRange(new ByteArrayInputStream(new byte[]{1,2,3,4}), type, 4, 0, 3, 4, "a".repeat(64)));
        mvc.perform(get("/api/v1/tracks/42/cover").with(user())).andExpect(status().isOk()).andExpect(content().contentType(type));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "\"other\"", "W/not-quoted"})
    void should_send_full_image_for_nonmatching_or_invalid_condition(String condition) throws Exception {
        mvc.perform(get("/api/v1/tracks/42/cover").with(user()).header("If-None-Match", condition))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1,2,3,4}));
    }

    @Test
    void should_return_problem_even_when_client_accepts_only_image() throws Exception {
        when(service.prepare(42L, actor)).thenThrow(new TrackCoverNotFoundException());
        mvc.perform(get("/api/v1/tracks/42/cover").with(user()).header("Accept", "image/png"))
                .andExpect(status().isNotFound()).andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void should_return_clean_problem_when_image_is_truncated_before_commit() throws Exception {
        when(service.open(cover)).thenReturn(new AudioObjectRange(new ByteArrayInputStream(new byte[]{1}), "image/png", 4, 0, 3, 4, "a".repeat(64)));
        mvc.perform(get("/api/v1/tracks/42/cover").with(user()))
                .andExpect(status().isServiceUnavailable()).andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("MEDIA_STORAGE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("ETag")).andExpect(header().string("Cache-Control", "no-store"));
    }
}

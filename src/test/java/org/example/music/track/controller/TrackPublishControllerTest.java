package org.example.music.track.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.config.SecurityConfig;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.dto.TrackPublishResponse;
import org.example.music.track.exception.TrackNotDraftException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.track.service.TrackPublishService;
import org.example.music.user.domain.UserRole;
import org.example.music.user.service.UserAuthorizationStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(TrackPublishController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class TrackPublishControllerTest {

    private static final String URL = "/api/v1/tracks/42/publish";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserAuthorizationStateService userAuthorizationStateService;

    @MockitoBean
    private TrackPublishService trackPublishService;

    @Test
    void should_publish_track_when_admin_request_is_valid() throws Exception {
        when(trackPublishService.publish(42L, 7))
                .thenReturn(new TrackPublishResponse(42L, "PUBLISHED", 4L, Instant.parse("2026-10-27T13:20:30Z")));

        mockMvc.perform(post(URL).with(adminAuthentication()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-27T13:20:30Z"));

        verify(trackPublishService).publish(42L, 7);
    }

    @Test
    void should_return_400_when_track_id_is_not_positive() throws Exception {
        mockMvc.perform(post("/api/v1/tracks/0/publish").with(adminAuthentication()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(trackPublishService);
    }

    @Test
    void should_return_401_when_authentication_is_missing() throws Exception {
        mockMvc.perform(post(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(trackPublishService);
    }

    @Test
    void should_return_403_when_user_is_not_admin() throws Exception {
        mockMvc.perform(post(URL).with(userAuthentication()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(trackPublishService);
    }

    @Test
    void should_return_404_when_track_does_not_exist() throws Exception {
        when(trackPublishService.publish(42L, 7)).thenThrow(new TrackNotFoundException(42L));

        mockMvc.perform(post(URL).with(adminAuthentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
    }

    @Test
    void should_return_409_when_track_is_not_draft() throws Exception {
        when(trackPublishService.publish(42L, 7)).thenThrow(new TrackNotDraftException(42L));

        mockMvc.perform(post(URL).with(adminAuthentication()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_DRAFT"));
    }

    @Test
    void should_return_409_without_version_fields_when_optimistic_lock_fails() throws Exception {
        when(trackPublishService.publish(42L, 7))
                .thenThrow(new ObjectOptimisticLockingFailureException(org.example.music.track.domain.TrackEntity.class, 42L));

        mockMvc.perform(post(URL).with(adminAuthentication()))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:music:track_version_conflict"))
                .andExpect(jsonPath("$.code").value("TRACK_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.expectedVersion").doesNotExist())
                .andExpect(jsonPath("$.actualVersion").doesNotExist());
    }

    @Test
    void should_return_503_when_track_store_is_unavailable() throws Exception {
        when(trackPublishService.publish(42L, 7))
                .thenThrow(new TrackStoreUnavailableException("Track store unavailable", new RuntimeException("db down")));

        mockMvc.perform(post(URL).with(adminAuthentication()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRACK_STORE_UNAVAILABLE"));
    }

    private static RequestPostProcessor adminAuthentication() {
        return authentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUserPrincipal(7, UserRole.ADMIN),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));
    }

    private static RequestPostProcessor userAuthentication() {
        return authentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUserPrincipal(8, UserRole.USER),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));
    }
}

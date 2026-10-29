package org.example.music.track.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.config.SecurityConfig;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.exception.TrackDeleteFailedException;
import org.example.music.track.exception.TrackMediaCleanupFailedException;
import org.example.music.track.exception.TrackNotArchivedException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.service.TrackDeleteService;
import org.example.music.user.domain.UserRole;
import org.example.music.user.service.UserAuthorizationStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(TrackDeleteController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class TrackDeleteControllerTest {

    // authentication(...) injects the final Authentication directly and bypasses TokenVersionAuthenticationFilter.
    // These tests verify controller contracts; real-token filter-chain behavior is covered by integration tests.

    private static final String URL = "/api/v1/tracks/42";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserAuthorizationStateService userAuthorizationStateService;

    @MockitoBean
    private TrackDeleteService trackDeleteService;

    @Test
    void should_delete_track_when_admin_request_is_valid() throws Exception {
        mockMvc.perform(delete(URL).with(adminAuthentication()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(trackDeleteService).delete(42L);
    }

    @Test
    void should_return_400_when_track_id_is_not_positive() throws Exception {
        mockMvc.perform(delete("/api/v1/tracks/0").with(adminAuthentication()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(trackDeleteService);
    }

    @Test
    void should_return_401_when_authentication_is_missing() throws Exception {
        mockMvc.perform(delete(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(trackDeleteService);
    }

    @Test
    void should_return_403_when_user_is_not_admin() throws Exception {
        mockMvc.perform(delete(URL).with(userAuthentication()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(trackDeleteService);
    }

    @Test
    void should_return_404_when_track_does_not_exist() throws Exception {
        doThrow(new TrackNotFoundException(42L)).when(trackDeleteService).delete(42L);

        mockMvc.perform(delete(URL).with(adminAuthentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
    }

    @Test
    void should_return_409_when_track_is_not_archived() throws Exception {
        doThrow(new TrackNotArchivedException(42L)).when(trackDeleteService).delete(42L);

        mockMvc.perform(delete(URL).with(adminAuthentication()))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:music:track_not_archived"))
                .andExpect(jsonPath("$.code").value("TRACK_NOT_ARCHIVED"));
    }

    @Test
    void should_return_503_when_media_cleanup_fails() throws Exception {
        doThrow(new TrackMediaCleanupFailedException(42L, new RuntimeException("storage down")))
                .when(trackDeleteService).delete(42L);

        mockMvc.perform(delete(URL).with(adminAuthentication()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:problem:music:track_media_cleanup_failed"))
                .andExpect(jsonPath("$.code").value("TRACK_MEDIA_CLEANUP_FAILED"));
    }

    @Test
    void should_return_503_when_database_delete_fails() throws Exception {
        doThrow(new TrackDeleteFailedException(42L, new RuntimeException("db down")))
                .when(trackDeleteService).delete(42L);

        mockMvc.perform(delete(URL).with(adminAuthentication()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:problem:music:track_delete_failed"))
                .andExpect(jsonPath("$.code").value("TRACK_DELETE_FAILED"));
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
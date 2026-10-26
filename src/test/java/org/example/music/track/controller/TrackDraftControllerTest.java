package org.example.music.track.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.config.SecurityConfig;
import org.example.music.track.dto.MediaAssetResponse;
import org.example.music.track.dto.TrackDraftResponse;
import org.example.music.track.exception.TrackDraftValidationException;
import org.example.music.track.exception.TrackNotDraftException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackVersionConflictException;
import org.example.music.track.service.TrackDraftService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(TrackDraftController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class TrackDraftControllerTest {

    private static final String URL = "/api/v1/tracks/42/draft";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TrackDraftService trackDraftService;

    @Test
    void should_update_draft_when_request_is_valid() throws Exception {
        when(trackDraftService.updateDraft(eq(42L), any(), eq(7)))
                .thenReturn(response());

        mockMvc.perform(patch(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "修正后的标题",
                                  "artistName": "修正后的艺术家",
                                  "albumName": null,
                                  "trackNumber": 3,
                                  "discNumber": null,
                                  "version": 3
                                }
                                """)
                        .with(adminAuthentication()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.title").value("修正后的标题"))
                .andExpect(jsonPath("$.artistName").value("修正后的艺术家"))
                .andExpect(jsonPath("$.albumName").doesNotExist())
                .andExpect(jsonPath("$.trackNumber").value(3))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.audio.bucket").doesNotExist());

        verify(trackDraftService).updateDraft(eq(42L), any(), eq(7));
    }

    @Test
    void should_return_400_when_version_is_missing() throws Exception {
        mockMvc.perform(patch(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "新标题"
                                }
                                """)
                        .with(adminAuthentication()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(trackDraftService);
    }

    @Test
    void should_return_400_when_title_is_blank() throws Exception {
        when(trackDraftService.updateDraft(eq(42L), any(), eq(7)))
                .thenThrow(new TrackDraftValidationException("title must not be blank"));

        mockMvc.perform(patch(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "   ",
                                  "version": 3
                                }
                                """)
                        .with(adminAuthentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void should_return_404_when_track_does_not_exist() throws Exception {
        when(trackDraftService.updateDraft(eq(42L), any(), eq(7)))
                .thenThrow(new TrackNotFoundException(42L));

        mockMvc.perform(patch(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": 3
                                }
                                """)
                        .with(adminAuthentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
    }

    @Test
    void should_return_409_when_track_is_not_draft() throws Exception {
        when(trackDraftService.updateDraft(eq(42L), any(), eq(7)))
                .thenThrow(new TrackNotDraftException(42L));

        mockMvc.perform(patch(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": 3
                                }
                                """)
                        .with(adminAuthentication()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_DRAFT"));
    }

    @Test
    void should_return_409_when_version_is_stale() throws Exception {
        when(trackDraftService.updateDraft(eq(42L), any(), eq(7)))
                .thenThrow(new TrackVersionConflictException(3L, 4L));

        mockMvc.perform(patch(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": 3
                                }
                                """)
                        .with(adminAuthentication()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRACK_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.expectedVersion").value(3))
                .andExpect(jsonPath("$.actualVersion").value(4));
    }

    @Test
    void should_return_401_when_authentication_is_missing() throws Exception {
        mockMvc.perform(patch(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": 3
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(trackDraftService);
    }

    @Test
    void should_return_403_when_user_is_not_admin() throws Exception {
        mockMvc.perform(patch(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": 3
                                }
                                """)
                        .with(userAuthentication()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(trackDraftService);
    }

    private static TrackDraftResponse response() {
        return new TrackDraftResponse(
                42L,
                "修正后的标题",
                "修正后的艺术家",
                null,
                215430L,
                3,
                null,
                4L,
                "DRAFT",
                new MediaAssetResponse(
                        51L,
                        "audio/mpeg",
                        8230144L,
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                ),
                null
        );
    }

    private static RequestPostProcessor adminAuthentication() {
        return SecurityMockMvcRequestPostProcessors.authentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("token")
                        .header("alg", "HS256")
                        .subject("7")
                        .claim("role", "ADMIN")
                        .build(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));
    }

    private static RequestPostProcessor userAuthentication() {
        return SecurityMockMvcRequestPostProcessors.authentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("token")
                        .header("alg", "HS256")
                        .subject("8")
                        .claim("role", "USER")
                        .build(),
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));
    }
}

package org.example.music.track.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.config.SecurityConfig;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.MediaImportEntity;
import org.example.music.track.domain.MetadataWarning;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.ImportConflictException;
import org.example.music.track.exception.TrackImportRateLimitedException;
import org.example.music.track.exception.UnsupportedAudioFormatException;
import org.example.music.track.exception.UploadTooLargeException;
import org.example.music.track.service.MediaImportService;
import org.example.music.track.service.TrackImportRateLimiter;
import org.example.music.track.service.TrackImportResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(TrackImportController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class TrackImportControllerTest {

    private static final UUID UPLOAD_ID = UUID.fromString("36bd81f4-2fa2-4d1c-a7c3-b6423690a3fa");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MediaImportService mediaImportService;

    @MockitoBean
    private TrackImportRateLimiter trackImportRateLimiter;

    @Test
    void should_import_track_when_admin_request_is_valid() throws Exception {
        when(mediaImportService.importTrack(eq(UPLOAD_ID), any(), eq(7)))
                .thenReturn(result(true));

        mockMvc.perform(multipart("/api/v1/track-imports")
                        .file(audioFile())
                        .param("uploadId", UPLOAD_ID.toString())
                        .with(adminAuthentication()))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.track.status").value("DRAFT"))
                .andExpect(jsonPath("$.track.title").value("Example Song"))
                .andExpect(jsonPath("$.metadataWarnings[0]").value("COVER_MISSING"))
                .andExpect(jsonPath("$.track.audio.bucket").doesNotExist());

        verify(trackImportRateLimiter).check(7);
    }

    @Test
    void should_return_existing_import_when_idempotent_retry_matches() throws Exception {
        when(mediaImportService.importTrack(eq(UPLOAD_ID), any(), eq(7)))
                .thenReturn(result(false));

        mockMvc.perform(multipart("/api/v1/track-imports")
                        .file(audioFile())
                        .param("uploadId", UPLOAD_ID.toString())
                        .with(adminAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void should_return_conflict_when_upload_id_is_reused_for_different_file() throws Exception {
        when(mediaImportService.importTrack(eq(UPLOAD_ID), any(), eq(7)))
                .thenThrow(new ImportConflictException(UPLOAD_ID, "uploadId is already used by another file"));

        mockMvc.perform(multipart("/api/v1/track-imports")
                        .file(audioFile())
                        .param("uploadId", UPLOAD_ID.toString())
                        .with(adminAuthentication()))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IMPORT_CONFLICT"));
    }

    @Test
    void should_return_401_when_authentication_is_missing() throws Exception {
        mockMvc.perform(multipart("/api/v1/track-imports")
                        .file(audioFile())
                        .param("uploadId", UPLOAD_ID.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(mediaImportService);
    }

    @Test
    void should_return_403_when_user_is_not_admin() throws Exception {
        mockMvc.perform(multipart("/api/v1/track-imports")
                        .file(audioFile())
                        .param("uploadId", UPLOAD_ID.toString())
                        .with(userAuthentication()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(mediaImportService);
    }

    @Test
    void should_return_400_when_upload_id_is_missing() throws Exception {
        mockMvc.perform(multipart("/api/v1/track-imports")
                        .file(audioFile())
                        .with(adminAuthentication()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void should_return_413_when_audio_file_is_too_large() throws Exception {
        when(mediaImportService.importTrack(eq(UPLOAD_ID), any(), eq(7)))
                .thenThrow(new UploadTooLargeException(209715200));

        mockMvc.perform(multipart("/api/v1/track-imports")
                        .file(audioFile())
                        .param("uploadId", UPLOAD_ID.toString())
                        .with(adminAuthentication()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"));
    }

    @Test
    void should_return_415_when_audio_format_is_unsupported() throws Exception {
        when(mediaImportService.importTrack(eq(UPLOAD_ID), any(), eq(7)))
                .thenThrow(new UnsupportedAudioFormatException("Only MP3 and FLAC audio files are supported"));

        mockMvc.perform(multipart("/api/v1/track-imports")
                        .file(audioFile())
                        .param("uploadId", UPLOAD_ID.toString())
                        .with(adminAuthentication()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_AUDIO_FORMAT"));
    }

    @Test
    void should_return_429_when_import_is_rate_limited() throws Exception {
        org.mockito.Mockito.doThrow(new TrackImportRateLimitedException(30))
                .when(trackImportRateLimiter).check(7);

        mockMvc.perform(multipart("/api/v1/track-imports")
                        .file(audioFile())
                        .param("uploadId", UPLOAD_ID.toString())
                        .with(adminAuthentication()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("TRACK_IMPORT_RATE_LIMITED"));

        verifyNoInteractions(mediaImportService);
    }

    private static MockMultipartFile uploadIdPart() {
        return new MockMultipartFile(
                "uploadId",
                "",
                "application/json",
                UPLOAD_ID.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private static MockMultipartFile audioFile() {
        return new MockMultipartFile(
                "file",
                "example.mp3",
                "audio/mpeg",
                new byte[] {'I', 'D', '3', 0, 0}
        );
    }

    private static RequestPostProcessor adminAuthentication() {
        return SecurityMockMvcRequestPostProcessors.authentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("token")
                        .header("alg", "HS256")
                        .subject("7")
                        .claim("role", "ADMIN")
                        .build(),
                java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));
    }

    private static RequestPostProcessor userAuthentication() {
        return SecurityMockMvcRequestPostProcessors.authentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("token")
                        .header("alg", "HS256")
                        .subject("8")
                        .claim("role", "USER")
                        .build(),
                java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));
    }

    private static TrackImportResult result(boolean created) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 25, 12, 0);
        MediaImportEntity importEntity = MediaImportEntity.start(
                UPLOAD_ID,
                "example.mp3",
                "audio/mpeg",
                now,
                7
        );
        importEntity.ready(1L, now);
        TrackEntity track = TrackEntity.createDraft(
                "Example Song",
                "Example Artist",
                null,
                215430L,
                1,
                1,
                1L,
                null,
                "{}",
                now,
                7
        );
        MediaAssetEntity audio = MediaAssetEntity.create(
                MediaAssetKind.AUDIO,
                "private-bucket",
                "audio/secret.mp3",
                "audio/mpeg",
                "example.mp3",
                10L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                now,
                7
        );
        return new TrackImportResult(
                importEntity,
                track,
                audio,
                null,
                Set.of(MetadataWarning.COVER_MISSING),
                created
        );
    }
}

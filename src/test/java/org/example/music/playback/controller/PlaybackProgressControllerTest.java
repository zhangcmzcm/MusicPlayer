package org.example.music.playback.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.config.SecurityConfig;
import org.example.music.playback.dto.SavePlaybackProgressRequest;
import org.example.music.playback.dto.SavePlaybackProgressResponse;
import org.example.music.playback.exception.*;
import org.example.music.playback.service.PlaybackProgressService;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.user.domain.UserRole;
import org.example.music.user.service.UserAuthorizationStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.slf4j.LoggerFactory;

@WebMvcTest(PlaybackProgressController.class)
@Import({SecurityConfig.class, PlaybackProgressRequestReader.class})
@ActiveProfiles("test")
class PlaybackProgressControllerTest {
    private static final String DEVICE = "bcf04f9c-3876-4a77-8f6d-632ebd78db2e";
    private static final String PATH = "/api/v1/users/me/playback-progress/";
    private static final String BODY = "{\"trackId\":123,\"positionSeconds\":83,\"sequence\":42}";
    @Autowired MockMvc mvc;
    @MockitoBean PlaybackProgressService service;
    @MockitoBean UserAuthorizationStateService authorizationState;
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(7, UserRole.USER);

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(actor, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @BeforeEach
    void setup() {
        when(service.save(anyString(), any(), eq(actor))).thenAnswer(invocation -> {
            SavePlaybackProgressRequest input = invocation.getArgument(1);
            return new SavePlaybackProgressResponse(invocation.getArgument(0), input.trackId(),
                    input.positionSeconds(), input.sequence(), Instant.parse("2026-11-15T00:00:00.123456Z"));
        });
    }

    @Test
    void should_return_five_fields_and_normalize_uuid() throws Exception {
        mvc.perform(put(PATH + DEVICE.toUpperCase(Locale.ROOT)).contentType("application/json;charset=UTF-8")
                        .content(BODY).with(authentication(auth())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.deviceId").value(DEVICE)).andExpect(jsonPath("$.trackId").value(123))
                .andExpect(jsonPath("$.positionSeconds").value(83)).andExpect(jsonPath("$.sequence").value(42))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-15T00:00:00.123456Z"))
                .andExpect(jsonPath("$.length()").value(5));
        verify(service).save(DEVICE, new SavePlaybackProgressRequest(123L, 83L, 42L), actor);
    }

    @Test
    void should_require_authentication_before_binding_invalid_input() throws Exception {
        mvc.perform(put(PATH + "invalid").contentType("text/plain").content("invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad", "1-1-1-1-1", "bcf04f9c38764a778f6d632ebd78db2e", "bcf04f9c-3876-4a77-8f6d-632ebd78db2g"})
    void should_reject_invalid_uuid(String device) throws Exception {
        mvc.perform(put(PATH + device).contentType("application/json").content(BODY).with(authentication(auth())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test
    void should_reject_invalid_body_and_media_type_before_service() throws Exception {
        mvc.perform(put(PATH + DEVICE).contentType("application/json").content(BODY.replace("83", "83.9"))
                        .with(authentication(auth())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(put(PATH + DEVICE).contentType("text/plain").content(BODY).with(authentication(auth())))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        verifyNoInteractions(service);
    }

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(new TrackNotFoundException(123L), 404, "TRACK_NOT_FOUND"),
                Arguments.of(new AccessDeniedException("denied"), 403, "FORBIDDEN"),
                Arguments.of(new PlaybackProgressValidationException(), 400, "VALIDATION_FAILED"),
                Arguments.of(new PlaybackProgressStaleSequenceException(43), 409, "PLAYBACK_PROGRESS_STALE_SEQUENCE"),
                Arguments.of(new PlaybackProgressSequenceConflictException(42), 409, "PLAYBACK_PROGRESS_SEQUENCE_CONFLICT"),
                Arguments.of(new PlaybackProgressRateLimitedException(59), 429, "PLAYBACK_PROGRESS_RATE_LIMITED"),
                Arguments.of(new PlaybackProgressRateLimitUnavailableException(new RuntimeException("private")), 503, "PLAYBACK_PROGRESS_RATE_LIMIT_UNAVAILABLE"),
                Arguments.of(new PlaybackProgressStoreUnavailableException(new RuntimeException("private")), 503, "PLAYBACK_PROGRESS_STORE_UNAVAILABLE"));
    }

    @ParameterizedTest
    @MethodSource("errors")
    void should_map_business_errors_without_exposing_causes(RuntimeException error, int status, String code) throws Exception {
        doThrow(error).when(service).save(anyString(), any(), any());
        var result = mvc.perform(put(PATH + DEVICE).contentType("application/json").content(BODY).with(authentication(auth())))
                .andExpect(status().is(status)).andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type").value("urn:problem:music:" + code.toLowerCase(Locale.ROOT)))
                .andExpect(content().string(not(containsString("private"))));
        if (error instanceof PlaybackProgressStaleSequenceException stale) {
            result.andExpect(jsonPath("$.currentSequence").value(stale.getCurrentSequence()));
        }
        if (error instanceof PlaybackProgressSequenceConflictException conflict) {
            result.andExpect(jsonPath("$.currentSequence").value(conflict.getCurrentSequence()));
        }
        if (status == 429) {
            result.andExpect(header().string("Retry-After", "59"));
        }
    }

    @Test
    void should_log_storage_failure_without_private_exception_details() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            doThrow(new PlaybackProgressStoreUnavailableException(new RuntimeException("private SQL password token")))
                    .when(service).save(anyString(), any(), any());
            mvc.perform(put(PATH + DEVICE).contentType("application/json").content(BODY).with(authentication(auth())))
                    .andExpect(status().isServiceUnavailable());
            assertFalse(appender.list.isEmpty());
            for (var event : appender.list) {
                assertFalse(event.getFormattedMessage().contains("private SQL"));
                assertNull(event.getThrowableProxy());
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}

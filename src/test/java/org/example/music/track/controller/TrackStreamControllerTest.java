package org.example.music.track.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.config.SecurityConfig;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.storage.domain.AudioByteRange;
import org.example.music.storage.domain.AudioObjectMetadata;
import org.example.music.storage.domain.AudioStreamStorage;
import org.example.music.storage.domain.VerifiedAudioStream;
import org.example.music.storage.exception.AudioObjectNotFoundException;
import org.example.music.storage.exception.AudioStorageUnavailableException;
import org.example.music.storage.exception.AudioStreamInconsistentException;

import org.example.music.track.service.PreparedTrackStream;
import org.example.music.track.service.TrackStreamSnapshot;
import org.example.music.track.service.TrackStreamService;
import org.example.music.track.web.TrackStreamErrorAdvice;
import org.example.music.track.web.TrackStreamResponseWriter;
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

@WebMvcTest(TrackStreamController.class)
@Import({SecurityConfig.class,GlobalExceptionHandler.class,TrackStreamResponseWriter.class,TrackStreamErrorAdvice.class})
class TrackStreamControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private TrackStreamService service;
    @MockitoBean private AudioStreamStorage storage;
    @MockitoBean private UserAuthorizationStateService authorization;
    private final AuthenticatedUserPrincipal actor=new AuthenticatedUserPrincipal(7,UserRole.USER);
    private final Instant modified=Instant.parse("2026-10-01T00:00:00Z");
    private final PreparedTrackStream prepared=new PreparedTrackStream(new TrackStreamSnapshot(42L,
            new AudioObjectMetadata("private","secret","audio/mpeg",10,"a".repeat(64)),modified),"\""+"a".repeat(64)+"\"",modified,true);
    private final byte[] data="0123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private RequestPostProcessor user() { return authentication(new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_USER")))); }
    @BeforeEach void setup() {
        when(service.prepare(eq(42L),eq(actor),any())).thenReturn(prepared);
        when(storage.open(eq(prepared.snapshot().metadata()),any())).thenAnswer(i->{
            AudioByteRange range=i.getArgument(1);byte[] bytes=range==null?data:Arrays.copyOfRange(data,(int)range.start(),(int)range.end()+1);
            var stream=mock(VerifiedAudioStream.class);when(stream.content()).thenReturn(new ByteArrayInputStream(bytes));when(stream.contentLength()).thenReturn((long)bytes.length);return stream;
        });
    }
    @Test void should_return_complete_audio_and_metadata() throws Exception {
        mvc.perform(get("/api/v1/tracks/42/stream").with(user())).andExpect(status().isOk()).andExpect(content().bytes(data))
                .andExpect(content().contentType("audio/mpeg")).andExpect(header().string("Content-Length","10"))
                .andExpect(header().string("Accept-Ranges","bytes")).andExpect(header().string("ETag",prepared.etag()))
                .andExpect(header().exists("Last-Modified")).andExpect(header().string("Cache-Control","private, no-cache"))
                .andExpect(header().string("Vary",org.hamcrest.Matchers.containsString("Authorization")))
                .andExpect(header().doesNotExist("Content-Range"));
    }
    @Test void should_return_exact_partial_audio() throws Exception {
        mvc.perform(get("/api/v1/tracks/42/stream").with(user()).header("Range","bytes=2-4"))
                .andExpect(status().isPartialContent()).andExpect(content().bytes(Arrays.copyOfRange(data,2,5)))
                .andExpect(header().string("Content-Length","3")).andExpect(header().string("Content-Range","bytes 2-4/10"));
    }
    @Test void should_prioritize_conditions_over_bad_range() throws Exception {
        mvc.perform(get("/api/v1/tracks/42/stream").with(user()).header("Range","bytes=bad").header("If-None-Match",prepared.etag()))
                .andExpect(status().isNotModified()).andExpect(content().string("")).andExpect(header().doesNotExist("Content-Length"));
        mvc.perform(get("/api/v1/tracks/42/stream").with(user()).header("Range","bytes=bad").header("If-Match","\"other\""))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"))
                .andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(storage);
        mvc.perform(get("/api/v1/tracks/42/stream").with(user()).header("Range","bytes=bad").header("If-Range","\"other\""))
                .andExpect(status().isOk()).andExpect(content().bytes(data));
    }
    @ParameterizedTest @ValueSource(strings={"bytes=10-","bytes=bad","bytes=-0","bytes=9-2"})
    void should_return_416_with_authorized_total_and_no_store(String range) throws Exception {
        mvc.perform(get("/api/v1/tracks/42/stream").with(user()).header("Range",range))
                .andExpect(status().isRequestedRangeNotSatisfiable()).andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("RANGE_NOT_SATISFIABLE"))
                .andExpect(header().string("Content-Range","bytes */10")).andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(storage);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","1.5","9223372036854775808"})
    void should_reject_invalid_path(String id) throws Exception {
        mvc.perform(get("/api/v1/tracks/"+id+"/stream").with(user())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));verifyNoInteractions(service,storage);
    }
    @Test void should_require_authentication_and_never_read_body_for_head() throws Exception {
        mvc.perform(get("/api/v1/tracks/42/stream")).andExpect(status().isUnauthorized());
        mvc.perform(head("/api/v1/tracks/42/stream").with(user()).header("Range","bytes=bad"))
                .andExpect(status().isOk()).andExpect(content().string(""))
                .andExpect(header().string("Content-Length","10")).andExpect(header().doesNotExist("Content-Range"));
        verifyNoInteractions(storage);
    }
    @Test void should_sanitize_get_race_and_strict_storage_failures() throws Exception {
        doThrow(new AudioObjectNotFoundException("private","secret")).when(storage).open(any(),any());
        mvc.perform(get("/api/v1/tracks/42/stream").with(user())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Track not found: 42"));
        doThrow(new AudioStreamInconsistentException()).when(storage).open(any(),any());
        mvc.perform(get("/api/v1/tracks/42/stream").with(user())).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AUDIO_STORAGE_INCONSISTENT"));
    }
    @Test void should_never_cache_storage_errors_as_304_and_clear_uncommitted_partial_headers() throws Exception {
        doThrow(new AudioStorageUnavailableException("failure",null)).when(service).prepare(eq(42L),eq(actor),any());
        mvc.perform(get("/api/v1/tracks/42/stream").with(user()).header("If-None-Match","*"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MEDIA_STORAGE_UNAVAILABLE"));
        doReturn(prepared).when(service).prepare(eq(42L),eq(actor),any());
        var stream=mock(VerifiedAudioStream.class);when(stream.content()).thenReturn(new ByteArrayInputStream(new byte[]{1}));when(stream.contentLength()).thenReturn(3L);
        doReturn(stream).when(storage).open(any(),any());
        mvc.perform(get("/api/v1/tracks/42/stream").with(user()).header("Range","bytes=0-2"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().contentType("application/problem+json"))
                .andExpect(header().doesNotExist("Content-Range")).andExpect(header().doesNotExist("ETag"));
        verify(stream).close();verify(stream,never()).completed();
    }
}

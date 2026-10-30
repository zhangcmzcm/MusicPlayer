package org.example.music.track.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.inOrder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import org.example.music.storage.domain.AudioByteRange;
import org.example.music.storage.domain.AudioObjectMetadata;
import org.example.music.storage.domain.AudioStreamStorage;
import org.example.music.storage.domain.VerifiedAudioStream;
import org.example.music.storage.exception.AudioStorageUnavailableException;
import org.example.music.track.service.PreparedTrackStream;
import org.example.music.track.service.TrackStreamSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class TrackStreamResponseWriterTest {
    private final AudioStreamStorage storage=mock(AudioStreamStorage.class);
    private final TrackStreamResponseWriter writer=new TrackStreamResponseWriter(storage);
    private final VerifiedAudioStream stream=mock(VerifiedAudioStream.class);
    private final PreparedTrackStream prepared=new PreparedTrackStream(new TrackStreamSnapshot(42L,
            new AudioObjectMetadata("private","secret","audio/mpeg",4,"a".repeat(64)),Instant.EPOCH),"\"tag\"",Instant.EPOCH,true);
    private void input(InputStream input) { when(storage.open(any(),any())).thenReturn(stream);when(stream.content()).thenReturn(input);when(stream.contentLength()).thenReturn(4L); }
    @Test void should_complete_and_close_only_after_successful_output() throws Exception {
        input(new ByteArrayInputStream(new byte[]{1,2,3,4}));var response=new MockHttpServletResponse();response.addHeader("Vary","Origin");
        writer.write(prepared,null,response);assertEquals("Origin, Authorization",response.getHeader("Vary"));
        assertArrayEquals(new byte[]{1,2,3,4},response.getContentAsByteArray());
        var order=inOrder(stream);order.verify(stream).completed();order.verify(stream).close();
    }
    @Test void should_clear_uncommitted_audio_headers_and_preserve_trace() throws Exception {
        input(new ByteArrayInputStream(new byte[]{1}));var response=new MockHttpServletResponse();response.setHeader("X-Trace-Id","test-trace");
        assertThrows(AudioStorageUnavailableException.class,()->writer.write(prepared,new AudioByteRange(0,3),response));
        assertNull(response.getHeader("Content-Range"));assertNull(response.getHeader("Content-Length"));assertNull(response.getHeader("ETag"));
        assertNull(response.getHeader("Last-Modified"));assertEquals(0,response.getContentAsByteArray().length);
        assertEquals("test-trace",response.getHeader("X-Trace-Id"));verify(stream).close();verify(stream,never()).completed();
    }
    @Test void should_close_on_first_read_failure() throws Exception {
        var source=mock(InputStream.class);when(source.read(any(byte[].class),anyInt(),anyInt())).thenThrow(new IOException("private/secret"));input(source);
        assertThrows(AudioStorageUnavailableException.class,()->writer.write(prepared,null,new MockHttpServletResponse()));
        verify(stream).close();verify(stream,never()).completed();
    }
    @Test void should_propagate_only_sanitized_io_after_commit() throws Exception {
        var source=mock(InputStream.class);when(source.read(any(byte[].class),anyInt(),anyInt())).thenReturn(1).thenThrow(new IOException("private/secret"));input(source);
        var response=new MockHttpServletResponse();response.setCommitted(true);
        var error=assertThrows(IOException.class,()->writer.write(prepared,null,response));assertEquals("Audio transfer interrupted",error.getMessage());assertNull(error.getCause());
        verify(stream).close();verify(stream,never()).completed();
    }
    @Test void should_close_without_completion_when_client_output_fails() throws Exception {
        input(new ByteArrayInputStream(new byte[]{1,2,3,4}));var response=spy(new MockHttpServletResponse());doThrow(new IOException("closed")).when(response).getOutputStream();
        assertThrows(AudioStorageUnavailableException.class,()->writer.write(prepared,null,response));verify(stream).close();verify(stream,never()).completed();
    }
    @Test void should_handle_close_failure_without_leaking_image_or_audio_headers() throws Exception {
        input(new ByteArrayInputStream(new byte[]{1,2,3,4}));doThrow(new IOException("close failure")).when(stream).close();var response=new MockHttpServletResponse();
        assertThrows(AudioStorageUnavailableException.class,()->writer.write(prepared,null,response));assertEquals(0,response.getContentAsByteArray().length);assertNull(response.getHeader("Content-Length"));
    }
}

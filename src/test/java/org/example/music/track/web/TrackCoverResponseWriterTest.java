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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.example.music.storage.domain.AudioObjectRange;
import org.example.music.storage.exception.AudioStorageUnavailableException;
import org.example.music.track.service.PreparedTrackCover;
import org.example.music.track.service.TrackCoverService;
import org.example.music.track.service.TrackCoverSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class TrackCoverResponseWriterTest {
    private final TrackCoverService service = mock(TrackCoverService.class);
    private final TrackCoverResponseWriter writer = new TrackCoverResponseWriter(service);
    private final PreparedTrackCover cover = new PreparedTrackCover(new TrackCoverSnapshot(42L, "private", "secret", "image/png", 4, "a".repeat(64)), "\"tag\"");

    private void stream(InputStream input, long length) {
        when(service.open(cover)).thenReturn(new AudioObjectRange(input, "image/png", length, 0, length-1, length, "a".repeat(64)));
    }

    @Test
    void should_close_source_and_preserve_existing_vary() throws Exception {
        var input = spy(new ByteArrayInputStream(new byte[]{1,2,3,4}));
        stream(input, 4);
        var response = new MockHttpServletResponse();
        response.addHeader("Vary", "Origin");
        writer.write(cover, response);
        assertArrayEquals(new byte[]{1,2,3,4}, response.getContentAsByteArray());
        assertEquals("Origin, Authorization", response.getHeader("Vary"));
        verify(input).close();
    }

    @Test
    void should_reset_buffer_and_close_source_before_commit() throws Exception {
        var input = spy(new ByteArrayInputStream(new byte[]{1}));
        stream(input, 4);
        var response = new MockHttpServletResponse();
        assertThrows(AudioStorageUnavailableException.class, () -> writer.write(cover, response));
        assertEquals(0, response.getContentAsByteArray().length);
        assertNull(response.getHeader("ETag"));
        assertNull(response.getHeader("Content-Length"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        verify(input).close();
    }

    @Test
    void should_close_source_on_first_read_failure() throws Exception {
        var input = mock(InputStream.class);
        when(input.read(any(byte[].class), anyInt(), anyInt())).thenThrow(new IOException("secret"));
        stream(input, 4);
        assertThrows(AudioStorageUnavailableException.class, () -> writer.write(cover, new MockHttpServletResponse()));
        verify(input).close();
    }

    @Test
    void should_propagate_sanitized_io_after_commit_and_close_source() throws Exception {
        var input = mock(InputStream.class);
        when(input.read(any(byte[].class), anyInt(), anyInt())).thenReturn(1).thenThrow(new IOException("private/secret"));
        stream(input, 4);
        var response = new MockHttpServletResponse();
        response.setCommitted(true);
        var error = assertThrows(IOException.class, () -> writer.write(cover, response));
        assertEquals("Cover transfer interrupted", error.getMessage());
        assertNull(error.getCause());
        verify(input).close();
    }

    @Test
    void should_release_source_when_output_fails() throws Exception {
        var input = spy(new ByteArrayInputStream(new byte[]{1,2,3,4}));
        stream(input, 4);
        var response = spy(new MockHttpServletResponse());
        doThrow(new IOException("disconnect")).when(response).getOutputStream();
        assertThrows(AudioStorageUnavailableException.class, () -> writer.write(cover, response));
        verify(input).close();
    }
    @Test
    void should_clear_uncommitted_response_when_close_fails() throws Exception {
        var input = spy(new ByteArrayInputStream(new byte[]{1,2,3,4}));
        doThrow(new IOException("close failure")).when(input).close();
        stream(input, 4);
        var response = new MockHttpServletResponse();
        response.setHeader("X-Trace-Id", "cover-test");
        assertThrows(AudioStorageUnavailableException.class, () -> writer.write(cover, response));
        assertEquals(0, response.getContentAsByteArray().length);
        assertEquals("cover-test", response.getHeader("X-Trace-Id"));
        assertNull(response.getHeader("Content-Length"));
        verify(input).close();
    }

}

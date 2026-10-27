package org.example.music.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void should_preserve_valid_client_trace_id() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "client.trace_id-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("client.trace_id-123", response.getHeader("X-Trace-Id"));
    }

    @Test
    void should_replace_invalid_client_trace_id_with_server_uuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "x".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String traceId = response.getHeader("X-Trace-Id");
        assertNotEquals("x".repeat(65), traceId);
        assertNotNull(traceId);
        assertNotNull(UUID.fromString(traceId));
    }

    @Test
    void should_generate_trace_id_when_header_is_blank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", " ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNotNull(response.getHeader("X-Trace-Id"));
        assertNotNull(UUID.fromString(response.getHeader("X-Trace-Id")));
    }
}

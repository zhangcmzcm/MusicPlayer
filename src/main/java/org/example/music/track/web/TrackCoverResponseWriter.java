package org.example.music.track.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import org.example.music.storage.exception.AudioStorageUnavailableException;
import org.example.music.track.service.PreparedTrackCover;
import org.example.music.track.service.TrackCoverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class TrackCoverResponseWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrackCoverResponseWriter.class);
    private final TrackCoverService service;

    public TrackCoverResponseWriter(TrackCoverService service) {
        this.service = service;
    }

    public static void cacheHeaders(PreparedTrackCover cover, HttpServletResponse response) {
        response.setHeader("ETag", cover.etag());
        response.setHeader("Cache-Control", "private, no-cache");
        var vary = new LinkedHashSet<String>();
        for (String header : response.getHeaders("Vary")) {
            Arrays.stream(header.split(",")).map(String::strip).filter(v -> !v.isEmpty()).forEach(vary::add);
        }
        if (vary.stream().noneMatch(v -> v.equalsIgnoreCase("Authorization"))) {
            vary.add("Authorization");
        }
        response.setHeader("Vary", String.join(", ", vary));
    }

    public void write(PreparedTrackCover cover, HttpServletResponse response) throws IOException {
        try (var object = service.open(cover)) {
            byte[] buffer = new byte[8192];
            long remaining = object.contentLength();
            int count = object.content().read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new EOFException();
            }
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(cover.snapshot().contentType());
            response.setContentLengthLong(object.contentLength());
            cacheHeaders(cover, response);
            var output = response.getOutputStream();
            while (remaining > 0) {
                if (count < 0) {
                    throw new EOFException();
                }
                if (count > 0) {
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
                if (remaining > 0) {
                    count = object.content().read(buffer, 0, (int) Math.min(buffer.length, remaining));
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Cover transfer interrupted, trackId={}, traceId={}, committed={}",
                    cover.snapshot().trackId(), MDC.get("traceId"), response.isCommitted());
            if (response.isCommitted()) {
                // Do not attach an SDK cause whose message could contain an object location.
                throw new IOException("Cover transfer interrupted");
            }
            clearImageHeaders(response);
            throw new AudioStorageUnavailableException("Cover transfer could not be completed", null);
        } catch (RuntimeException exception) {
            if (response.isCommitted()) {
                LOGGER.warn("Cover transfer failed, trackId={}, traceId={}", cover.snapshot().trackId(), MDC.get("traceId"));
                throw new IOException("Cover transfer interrupted");
            }
            clearImageHeaders(response);
            throw exception;
        }
    }

    public static void clearImageHeaders(HttpServletResponse response) {
        var retained = new LinkedHashMap<String, List<String>>();
        for (String name : response.getHeaderNames()) {
            if (!name.equalsIgnoreCase("Content-Length") && !name.equalsIgnoreCase("Content-Type")
                    && !name.equalsIgnoreCase("ETag") && !name.equalsIgnoreCase("Cache-Control")) {
                retained.put(name, List.copyOf(response.getHeaders(name)));
            }
        }
        response.reset();
        retained.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setHeader("Cache-Control", "no-store");
    }
}

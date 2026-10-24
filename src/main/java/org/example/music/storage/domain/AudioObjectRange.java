package org.example.music.storage.domain;

import java.io.IOException;
import java.io.InputStream;

public record AudioObjectRange(
        InputStream content,
        String contentType,
        long contentLength,
        long rangeStart,
        long rangeEnd,
        long totalSize,
        String sha256
) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        content.close();
    }
}

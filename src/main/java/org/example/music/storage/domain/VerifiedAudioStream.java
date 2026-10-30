package org.example.music.storage.domain;

import java.io.IOException;
import java.io.InputStream;

public interface VerifiedAudioStream extends AutoCloseable {
    InputStream content();
    long contentLength();
    void completed();
    @Override void close() throws IOException;
}

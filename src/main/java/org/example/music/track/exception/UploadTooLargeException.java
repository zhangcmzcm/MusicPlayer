package org.example.music.track.exception;

public class UploadTooLargeException extends RuntimeException {

    private final long maxBytes;

    public UploadTooLargeException(long maxBytes) {
        super("Audio upload exceeds the configured size limit");
        this.maxBytes = maxBytes;
    }

    public long getMaxBytes() {
        return maxBytes;
    }
}

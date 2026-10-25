package org.example.music.track.exception;

public class TrackImportRateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public TrackImportRateLimitedException(long retryAfterSeconds) {
        super("Track import rate limit exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

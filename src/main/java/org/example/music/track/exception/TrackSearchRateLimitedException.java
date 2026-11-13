package org.example.music.track.exception;

public class TrackSearchRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public TrackSearchRateLimitedException(long retryAfterSeconds) {
        super("歌曲搜索请求过于频繁，请稍后重试");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

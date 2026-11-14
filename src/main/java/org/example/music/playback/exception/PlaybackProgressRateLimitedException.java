package org.example.music.playback.exception;

public class PlaybackProgressRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public PlaybackProgressRateLimitedException(long retryAfterSeconds) {
        super("播放进度保存请求过于频繁，请稍后重试");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

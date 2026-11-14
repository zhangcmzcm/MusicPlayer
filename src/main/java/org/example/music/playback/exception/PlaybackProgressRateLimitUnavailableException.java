package org.example.music.playback.exception;

public class PlaybackProgressRateLimitUnavailableException extends RuntimeException {
    public PlaybackProgressRateLimitUnavailableException(Throwable cause) {
        super("播放进度保存额度暂时无法确认，请稍后重试", cause);
    }
}

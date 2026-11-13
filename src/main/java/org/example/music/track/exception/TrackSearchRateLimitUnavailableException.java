package org.example.music.track.exception;

public class TrackSearchRateLimitUnavailableException extends RuntimeException {
    public TrackSearchRateLimitUnavailableException(Throwable cause) {
        super("歌曲搜索额度暂时无法确认，请稍后重试", cause);
    }
}

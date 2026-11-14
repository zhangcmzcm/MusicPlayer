package org.example.music.playback.exception;

public class PlaybackProgressValidationException extends RuntimeException {
    public PlaybackProgressValidationException() {
        super("播放进度请求参数不合法");
    }
}

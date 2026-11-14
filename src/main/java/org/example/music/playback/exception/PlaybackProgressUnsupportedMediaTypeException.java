package org.example.music.playback.exception;

public class PlaybackProgressUnsupportedMediaTypeException extends RuntimeException {
    public PlaybackProgressUnsupportedMediaTypeException() {
        super("播放进度请求需要 application/json");
    }
}

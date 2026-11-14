package org.example.music.playback.exception;

public class PlaybackProgressStoreUnavailableException extends RuntimeException {
    public PlaybackProgressStoreUnavailableException(Throwable cause) {
        super("播放进度暂时无法保存，请稍后重试", cause);
    }
}

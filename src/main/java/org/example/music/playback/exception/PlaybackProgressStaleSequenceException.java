package org.example.music.playback.exception;

public class PlaybackProgressStaleSequenceException extends RuntimeException {
    private final long currentSequence;

    public PlaybackProgressStaleSequenceException(long currentSequence) {
        super("该设备已保存更新的播放状态");
        this.currentSequence = currentSequence;
    }

    public long getCurrentSequence() {
        return currentSequence;
    }
}

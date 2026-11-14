package org.example.music.playback.exception;

public class PlaybackProgressSequenceConflictException extends RuntimeException {
    private final long currentSequence;

    public PlaybackProgressSequenceConflictException(long currentSequence) {
        super("同一事件序号对应的播放内容发生冲突");
        this.currentSequence = currentSequence;
    }

    public long getCurrentSequence() {
        return currentSequence;
    }
}

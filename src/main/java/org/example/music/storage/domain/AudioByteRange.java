package org.example.music.storage.domain;

public record AudioByteRange(long start, long end) {
    public AudioByteRange {
        if (start < 0 || end < start || end == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid normalized audio range");
        }
    }
    public long length() { return end - start + 1; }
}

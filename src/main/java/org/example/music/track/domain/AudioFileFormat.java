package org.example.music.track.domain;

public enum AudioFileFormat {
    MP3("mp3", "audio/mpeg"),
    FLAC("flac", "audio/flac");

    private final String extension;
    private final String contentType;

    AudioFileFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }
}

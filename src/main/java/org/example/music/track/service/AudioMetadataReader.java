package org.example.music.track.service;

import java.nio.file.Path;
import org.example.music.track.domain.AudioFileFormat;

public interface AudioMetadataReader {

    AudioMetadataResult extract(Path audioFile, AudioFileFormat format, Path coverTarget);
}

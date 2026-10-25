package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.example.music.track.domain.AudioFileFormat;
import org.example.music.track.exception.UnsupportedAudioFormatException;
import org.example.music.track.exception.UploadTooLargeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class AudioFileValidatorTest {

    @TempDir
    Path tempDir;

    private final AudioFileValidator validator = new AudioFileValidator(new TrackImportProperties(
            "build/test-import",
100000,
            256,
            10
    ));

    @Test
    void should_accept_mp3_when_extension_mime_and_magic_match() throws Exception {
        Path audio = AudioTestFixtures.minimalMp3(tempDir);

        AudioFileFormat format = validator.validateFile(audio, AudioFileFormat.MP3, "audio/mpeg");

        assertEquals(AudioFileFormat.MP3, format);
    }

    @Test
    void should_accept_flac_when_extension_mime_and_magic_match() throws Exception {
        Path audio = AudioTestFixtures.minimalFlac(tempDir);

        AudioFileFormat format = validator.validateFile(audio, AudioFileFormat.FLAC, "audio/flac");

        assertEquals(AudioFileFormat.FLAC, format);
    }

    @Test
    void should_reject_file_when_mime_does_not_match_extension() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "song.mp3",
                "audio/flac",
                new byte[] {'I', 'D', '3'}
        );

        assertThrows(UnsupportedAudioFormatException.class, () -> validator.validateRequest(file));
    }

    @Test
    void should_reject_file_when_magic_does_not_match_declared_format() throws Exception {
        Path audio = AudioTestFixtures.minimalMp3(tempDir);

        assertThrows(
                UnsupportedAudioFormatException.class,
                () -> validator.validateFile(audio, AudioFileFormat.FLAC, "audio/flac")
        );
    }

    @Test
    void should_reject_file_when_size_exceeds_limit() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "song.mp3",
                "audio/mpeg",
                new byte[100001]
        );

        assertThrows(UploadTooLargeException.class, () -> validator.validateRequest(file));
    }
}

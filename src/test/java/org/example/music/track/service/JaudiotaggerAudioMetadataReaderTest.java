package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.example.music.track.domain.AudioFileFormat;
import org.example.music.track.domain.MetadataWarning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JaudiotaggerAudioMetadataReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void should_extract_mp3_metadata_and_cover_when_tags_are_present() throws Exception {
        Path audio = AudioTestFixtures.taggedMp3(tempDir);
        Path cover = tempDir.resolve("cover.img");
        JaudiotaggerAudioMetadataReader reader = reader(20);

        AudioMetadataResult result = reader.extract(audio, AudioFileFormat.MP3, cover);

        assertEquals("Fixture Song", result.metadata().rawTitle());
        assertEquals("Fixture Artist", result.metadata().rawArtistName());
        assertEquals("Fixture Album", result.metadata().rawAlbumName());
        assertEquals(2, result.metadata().rawTrackNumber());
        assertEquals(1, result.metadata().rawDiscNumber());
        assertTrue(result.metadata().durationMs() > 0);
        assertTrue(result.artwork().isPresent());
        assertFalse(result.artworkTooLarge());
        assertEquals(4, result.artwork().orElseThrow().sizeBytes());
        assertTrue(java.nio.file.Files.exists(cover));
    }

    @Test
    void should_extract_flac_duration_when_file_is_valid() throws Exception {
        Path audio = AudioTestFixtures.minimalFlac(tempDir);
        Path cover = tempDir.resolve("cover.img");

        AudioMetadataResult result = reader(20).extract(audio, AudioFileFormat.FLAC, cover);

        assertTrue(result.metadata().durationMs() > 0);
        assertNull(result.metadata().rawTitle());
        assertTrue(result.artwork().isEmpty());
        assertFalse(result.artworkTooLarge());
    }

    @Test
    void should_report_artwork_too_large_when_embedded_cover_exceeds_limit() throws Exception {
        Path audio = AudioTestFixtures.taggedMp3(tempDir);
        Path cover = tempDir.resolve("cover.img");

        AudioMetadataResult result = reader(2).extract(audio, AudioFileFormat.MP3, cover);

        assertTrue(result.artwork().isEmpty());
        assertTrue(result.artworkTooLarge());
    }

    private JaudiotaggerAudioMetadataReader reader(long coverMaxBytes) {
        return new JaudiotaggerAudioMetadataReader(new TrackImportProperties(
                tempDir.toString(),
                200L * 1024 * 1024,
                coverMaxBytes,
                10
        ));
    }
}

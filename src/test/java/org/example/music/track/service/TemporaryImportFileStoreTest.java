package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemporaryImportFileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void should_delete_audio_cover_and_workspace_when_import_finishes() throws Exception {
        TemporaryImportFileStore store = new TemporaryImportFileStore(new TrackImportProperties(
                tempDir.toString(),
                1024,
                256,
                10
        ));

        Path audio;
        Path cover;
        try (TemporaryImportFileStore.Workspace workspace = store.createWorkspace()) {
            audio = workspace.createAudioFile("mp3");
            cover = workspace.createCoverFile("img");
            Files.writeString(audio, "audio");
            Files.writeString(cover, "cover");
            assertTrue(Files.exists(audio));
            assertTrue(Files.exists(cover));
        }

        assertFalse(Files.exists(audio));
        assertFalse(Files.exists(cover));
    }
}

package org.example.music.track.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TemporaryImportFileStore {

    private final Path root;

    public TemporaryImportFileStore(TrackImportProperties properties) {
        this.root = Path.of(Objects.requireNonNull(properties.tempDir(), "tempDir"));
    }

    public Workspace createWorkspace() throws IOException {
        Files.createDirectories(root);
        return new Workspace(Files.createTempDirectory(root, "track-import-"));
    }

    public static final class Workspace implements AutoCloseable {

        private static final Logger LOGGER = LoggerFactory.getLogger(Workspace.class);

        private final Path directory;
        private final List<Path> files = new ArrayList<>();

        private Workspace(Path directory) {
            this.directory = directory;
        }

        public Path createAudioFile(String extension) throws IOException {
            return register(Files.createTempFile(directory, "audio-", "." + extension));
        }

        public Path createCoverFile(String extension) throws IOException {
            return register(Files.createTempFile(directory, "cover-", "." + extension));
        }

        private Path register(Path path) {
            files.add(path);
            return path;
        }

        @Override
        public void close() {
            for (Path file : files) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException exception) {
                    LOGGER.warn("Temporary import file could not be deleted, path={}", file, exception);
                }
            }
            try {
                Files.deleteIfExists(directory);
            } catch (IOException exception) {
                LOGGER.warn("Temporary import directory could not be deleted, path={}", directory, exception);
            }
        }
    }
}

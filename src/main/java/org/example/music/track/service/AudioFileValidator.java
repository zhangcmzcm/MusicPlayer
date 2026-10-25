package org.example.music.track.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.example.music.track.domain.AudioFileFormat;
import org.example.music.track.exception.UnsupportedAudioFormatException;
import org.example.music.track.exception.UploadTooLargeException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class AudioFileValidator {

    private static final int FLAC_MAGIC_LENGTH = 4;

    private final TrackImportProperties properties;

    public AudioFileValidator(TrackImportProperties properties) {
        this.properties = properties;
    }

    public AudioFileFormat validateRequest(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedAudioFormatException("Audio file is required");
        }
        if (file.getSize() > properties.audioMaxBytes()) {
            throw new UploadTooLargeException(properties.audioMaxBytes());
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new UnsupportedAudioFormatException("Audio filename is required");
        }
        AudioFileFormat format = fromExtension(filename);
        validateContentType(format, file.getContentType());
        return format;
    }

    public AudioFileFormat validateFile(Path path, AudioFileFormat declaredFormat, String contentType) {
        try {
            if (Files.size(path) > properties.audioMaxBytes()) {
                throw new UploadTooLargeException(properties.audioMaxBytes());
            }
            byte[] header = readHeader(path, FLAC_MAGIC_LENGTH);
            AudioFileFormat magicFormat = fromMagic(header);
            if (magicFormat != declaredFormat) {
                throw new UnsupportedAudioFormatException("Audio file content does not match its extension and MIME type");
            }
            validateContentType(magicFormat, contentType);
            return magicFormat;
        } catch (IOException exception) {
            throw new UnsupportedAudioFormatException("Audio file could not be read");
        }
    }

    public AudioFileFormat validateFilename(String filename, String contentType) {
        AudioFileFormat format = fromExtension(filename);
        validateContentType(format, contentType);
        return format;
    }

    private AudioFileFormat fromExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "mp3" -> AudioFileFormat.MP3;
            case "flac" -> AudioFileFormat.FLAC;
            default -> throw new UnsupportedAudioFormatException("Only MP3 and FLAC audio files are supported");
        };
    }

    private void validateContentType(AudioFileFormat format, String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean supported = switch (format) {
            case MP3 -> normalized.equals("audio/mpeg");
            case FLAC -> normalized.equals("audio/flac") || normalized.equals("audio/x-flac");
        };
        if (!supported) {
            throw new UnsupportedAudioFormatException("Audio MIME type does not match the file extension");
        }
    }

    private AudioFileFormat fromMagic(byte[] header) {
        if (header.length >= 3
                && header[0] == 'I'
                && header[1] == 'D'
                && header[2] == '3') {
            return AudioFileFormat.MP3;
        }
        if (header.length >= 2
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xE0) == 0xE0) {
            return AudioFileFormat.MP3;
        }
        if (header.length >= FLAC_MAGIC_LENGTH
                && header[0] == 'f'
                && header[1] == 'L'
                && header[2] == 'a'
                && header[3] == 'C') {
            return AudioFileFormat.FLAC;
        }
        throw new UnsupportedAudioFormatException("Audio file magic bytes are not supported");
    }

    private byte[] readHeader(Path path, int length) throws IOException {
        byte[] header = new byte[length];
        try (InputStream input = Files.newInputStream(path)) {
            int read = input.readNBytes(header, 0, header.length);
            if (read <= 0) {
                throw new UnsupportedAudioFormatException("Audio file is empty");
            }
        }
        return header;
    }
}

package org.example.music.track.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.example.music.track.domain.AudioFileFormat;
import org.example.music.track.exception.AudioMetadataInvalidException;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.springframework.stereotype.Component;

@Component
public class JaudiotaggerAudioMetadataReader implements AudioMetadataReader {

    private final TrackImportProperties properties;

    public JaudiotaggerAudioMetadataReader(TrackImportProperties properties) {
        this.properties = properties;
    }

    @Override
    public AudioMetadataResult extract(Path audioFile, AudioFileFormat format, Path coverTarget) {
        try {
            AudioFile file = AudioFileIO.read(audioFile.toFile());
            AudioHeader header = file.getAudioHeader();
            if (header == null || header.getPreciseTrackLength() <= 0) {
                throw new AudioMetadataInvalidException("Audio duration could not be determined");
            }

            Tag tag = file.getTag();
            String title = text(tag, FieldKey.TITLE);
            String artistName = text(tag, FieldKey.ARTIST);
            String albumName = text(tag, FieldKey.ALBUM);
            Integer trackNumber = positiveNumber(text(tag, FieldKey.TRACK));
            Integer discNumber = positiveNumber(text(tag, FieldKey.DISC_NO));
            long durationMs = Math.round(header.getPreciseTrackLength() * 1000.0);

            Optional<ExtractedArtwork> artwork = Optional.empty();
            boolean artworkTooLarge = false;
            Artwork embeddedArtwork = tag == null ? null : tag.getFirstArtwork();
            if (embeddedArtwork != null && embeddedArtwork.getBinaryData() != null) {
                byte[] binaryData = embeddedArtwork.getBinaryData();
                if (binaryData.length > properties.coverMaxBytes()) {
                    artworkTooLarge = true;
                } else {
                    Files.write(coverTarget, binaryData);
                    artwork = Optional.of(new ExtractedArtwork(
                            coverTarget,
                            normalizeContentType(embeddedArtwork.getMimeType()),
                            binaryData.length
                    ));
                }
            }

            Map<String, Object> rawMetadata = new LinkedHashMap<>();
            rawMetadata.put("format", format.name());
            rawMetadata.put("title", title);
            rawMetadata.put("artist", artistName);
            rawMetadata.put("album", albumName);
            rawMetadata.put("trackNumber", trackNumber);
            rawMetadata.put("discNumber", discNumber);
            rawMetadata.put("durationMs", durationMs);
            rawMetadata.put("artwork", embeddedArtwork == null
                    ? null
                    : Map.of(
                            "contentType", normalizeContentType(embeddedArtwork.getMimeType()),
                            "sizeBytes", embeddedArtwork.getBinaryData() == null
                                    ? 0
                                    : embeddedArtwork.getBinaryData().length
                    ));

            return new AudioMetadataResult(
                    new AudioMetadata(
                            format,
                            title,
                            artistName,
                            albumName,
                            durationMs,
                            trackNumber,
                            discNumber,
                            rawMetadata
                    ),
                    artwork,
                    artworkTooLarge
            );
        } catch (AudioMetadataInvalidException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AudioMetadataInvalidException("Audio metadata could not be extracted", exception);
        }
    }

    private String text(Tag tag, FieldKey key) {
        if (tag == null) {
            return null;
        }
        try {
            String value = tag.getFirst(key);
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Integer positiveNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        int slash = normalized.indexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(0, slash);
        }
        try {
            int parsed = Integer.parseInt(normalized);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String normalizeContentType(String mimeType) {
        return mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
    }
}

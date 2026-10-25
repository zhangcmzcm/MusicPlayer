package org.example.music.track.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.images.StandardArtwork;

public final class AudioTestFixtures {

    private AudioTestFixtures() {
    }

    public static Path minimalMp3(Path directory) throws IOException {
        Path path = directory.resolve("minimal.mp3");
        byte[] bytes = new byte[417 * 20];
        for (int index = 0; index < 20; index++) {
            int offset = index * 417;
            bytes[offset] = (byte) 0xFF;
            bytes[offset + 1] = (byte) 0xFB;
            bytes[offset + 2] = (byte) 0x90;
            bytes[offset + 3] = 0;
        }
        Files.write(path, bytes);
        return path;
    }

    public static Path minimalFlac(Path directory) throws IOException {
        Path path = directory.resolve("minimal.flac");
        ByteBuffer buffer = ByteBuffer.allocate(42 + 8192);
        buffer.put((byte) 'f');
        buffer.put((byte) 'L');
        buffer.put((byte) 'a');
        buffer.put((byte) 'C');
        buffer.put((byte) 0x80);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        buffer.put((byte) 34);
        buffer.putShort((short) 4096);
        buffer.putShort((short) 4096);
        buffer.put(new byte[6]);
        long packed = ((long) 44100 << 44) | ((long) 0 << 41) | ((long) 15 << 36) | 44100L;
        buffer.putLong(packed);
        buffer.put(new byte[16 + 8192]);
        Files.write(path, buffer.array());
        return path;
    }

    public static Path taggedMp3(Path directory) throws Exception {
        Path path = minimalMp3(directory);
        AudioFile audioFile = AudioFileIO.read(path.toFile());
        var tag = audioFile.createDefaultTag();
        tag.setField(FieldKey.TITLE, "Fixture Song");
        tag.setField(FieldKey.ARTIST, "Fixture Artist");
        tag.setField(FieldKey.ALBUM, "Fixture Album");
        tag.setField(FieldKey.TRACK, "2");
        tag.setField(FieldKey.DISC_NO, "1");
        StandardArtwork artwork = new StandardArtwork();
        artwork.setBinaryData(new byte[] {1, 2, 3, 4});
        artwork.setMimeType("image/jpeg");
        tag.setField(artwork);
        audioFile.setTag(tag);
        AudioFileIO.write(audioFile);
        return path;
    }
}

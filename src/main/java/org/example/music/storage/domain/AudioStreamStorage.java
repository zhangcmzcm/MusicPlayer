package org.example.music.storage.domain;

public interface AudioStreamStorage {
    VerifiedAudioStream open(AudioObjectMetadata expected, AudioByteRange range);
}

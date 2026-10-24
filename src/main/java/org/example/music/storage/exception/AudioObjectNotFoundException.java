package org.example.music.storage.exception;

public class AudioObjectNotFoundException extends AudioStorageException {

    public AudioObjectNotFoundException(String bucket, String objectKey) {
        super("Audio object not found: " + bucket + "/" + objectKey);
    }
}

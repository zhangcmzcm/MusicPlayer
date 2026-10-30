package org.example.music.track.service;

import java.time.Instant;

public record PreparedTrackStream(TrackStreamSnapshot snapshot, String etag, Instant lastModified, boolean dateStrong) { }

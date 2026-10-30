CREATE INDEX ix_tracks_created_at ON tracks (created_at);
CREATE INDEX ix_tracks_status_title ON tracks (status, title);
CREATE INDEX ix_tracks_status_artist_name ON tracks (status, artist_name);
CREATE INDEX ix_tracks_status_created_at ON tracks (status, created_at);

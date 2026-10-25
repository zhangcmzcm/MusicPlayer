package org.example.music.track.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tracks")
public class TrackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "artist_name", nullable = false, length = 255)
    private String artistName;

    @Column(name = "album_name", length = 255)
    private String albumName;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "track_number")
    private Integer trackNumber;

    @Column(name = "disc_number")
    private Integer discNumber;

    @Column(name = "audio_asset_id", nullable = false)
    private Long audioAssetId;

    @Column(name = "cover_asset_id")
    private Long coverAssetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "id3_metadata")
    private String id3Metadata;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TrackStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false)
    private Integer createdBy;

    @Column(name = "updated_by", nullable = false)
    private Integer updatedBy;

    protected TrackEntity() {
    }

    private TrackEntity(
            String title,
            String artistName,
            String albumName,
            Long durationMs,
            Integer trackNumber,
            Integer discNumber,
            Long audioAssetId,
            Long coverAssetId,
            String id3Metadata,
            LocalDateTime createdAt,
            Integer createdBy
    ) {
        this.title = title;
        this.artistName = artistName;
        this.albumName = albumName;
        this.durationMs = durationMs;
        this.trackNumber = trackNumber;
        this.discNumber = discNumber;
        this.audioAssetId = audioAssetId;
        this.coverAssetId = coverAssetId;
        this.id3Metadata = id3Metadata;
        this.status = TrackStatus.DRAFT;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    public static TrackEntity createDraft(
            String title,
            String artistName,
            String albumName,
            Long durationMs,
            Integer trackNumber,
            Integer discNumber,
            Long audioAssetId,
            Long coverAssetId,
            String id3Metadata,
            LocalDateTime createdAt,
            Integer createdBy
    ) {
        return new TrackEntity(
                title,
                artistName,
                albumName,
                durationMs,
                trackNumber,
                discNumber,
                audioAssetId,
                coverAssetId,
                id3Metadata,
                createdAt,
                createdBy
        );
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getArtistName() {
        return artistName;
    }

    public String getAlbumName() {
        return albumName;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Integer getTrackNumber() {
        return trackNumber;
    }

    public Integer getDiscNumber() {
        return discNumber;
    }

    public Long getAudioAssetId() {
        return audioAssetId;
    }

    public Long getCoverAssetId() {
        return coverAssetId;
    }

    public String getId3Metadata() {
        return id3Metadata;
    }

    public TrackStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Integer getCreatedBy() {
        return createdBy;
    }

    public Integer getUpdatedBy() {
        return updatedBy;
    }
}

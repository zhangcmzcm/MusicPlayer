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

@Entity
@Table(name = "media_assets")
public class MediaAssetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private MediaAssetKind kind;

    @Column(name = "bucket", nullable = false, length = 100)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", nullable = false)
    private Integer createdBy;

    protected MediaAssetEntity() {
    }

    private MediaAssetEntity(
            MediaAssetKind kind,
            String bucket,
            String objectKey,
            String contentType,
            String originalFilename,
            Long sizeBytes,
            String sha256,
            LocalDateTime createdAt,
            Integer createdBy
    ) {
        this.kind = kind;
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.originalFilename = originalFilename;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public static MediaAssetEntity create(
            MediaAssetKind kind,
            String bucket,
            String objectKey,
            String contentType,
            String originalFilename,
            Long sizeBytes,
            String sha256,
            LocalDateTime createdAt,
            Integer createdBy
    ) {
        return new MediaAssetEntity(
                kind,
                bucket,
                objectKey,
                contentType,
                originalFilename,
                sizeBytes,
                sha256,
                createdAt,
                createdBy
        );
    }

    public Long getId() {
        return id;
    }

    public MediaAssetKind getKind() {
        return kind;
    }

    public String getBucket() {
        return bucket;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Integer getCreatedBy() {
        return createdBy;
    }
}

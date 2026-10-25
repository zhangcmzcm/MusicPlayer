package org.example.music.track.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "media_imports")
public class MediaImportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "upload_id", nullable = false, length = 36)
    private UUID uploadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MediaImportStatus status;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "track_id")
    private Long trackId;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false)
    private Integer createdBy;

    protected MediaImportEntity() {
    }

    private MediaImportEntity(
            UUID uploadId,
            String originalFilename,
            String contentType,
            LocalDateTime createdAt,
            Integer createdBy
    ) {
        this.uploadId = uploadId;
        this.status = MediaImportStatus.UPLOADING;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.createdBy = createdBy;
    }

    public static MediaImportEntity start(
            UUID uploadId,
            String originalFilename,
            String contentType,
            LocalDateTime createdAt,
            Integer createdBy
    ) {
        return new MediaImportEntity(uploadId, originalFilename, contentType, createdAt, createdBy);
    }

    public void uploaded(long sizeBytes, String sha256, LocalDateTime updatedAt) {
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.status = MediaImportStatus.VALIDATING;
        this.updatedAt = updatedAt;
    }

    public void extracting(LocalDateTime updatedAt) {
        this.status = MediaImportStatus.EXTRACTING;
        this.updatedAt = updatedAt;
    }

    public void ready(Long trackId, LocalDateTime updatedAt) {
        this.status = MediaImportStatus.READY;
        this.trackId = trackId;
        this.failureCode = null;
        this.failureReason = null;
        this.updatedAt = updatedAt;
    }

    public void failed(String failureCode, String failureReason, LocalDateTime updatedAt) {
        this.status = MediaImportStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.updatedAt = updatedAt;
    }

    public void retry(String originalFilename, String contentType, LocalDateTime updatedAt) {
        this.status = MediaImportStatus.UPLOADING;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = null;
        this.sha256 = null;
        this.trackId = null;
        this.failureCode = null;
        this.failureReason = null;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getUploadId() {
        return uploadId;
    }

    public MediaImportStatus getStatus() {
        return status;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public Long getTrackId() {
        return trackId;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
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
}

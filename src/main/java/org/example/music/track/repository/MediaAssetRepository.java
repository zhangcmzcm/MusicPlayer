package org.example.music.track.repository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.example.music.track.domain.MediaAssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetRepository extends JpaRepository<MediaAssetEntity, Long> {

    Optional<MediaAssetEntity> findByBucketAndObjectKey(String bucket, String objectKey);

    @Query("select new org.example.music.track.repository.TrackListCoverRow(a.id, a.kind, a.contentType, a.sizeBytes, a.sha256, a.bucket, a.objectKey) from MediaAssetEntity a where a.id in :ids")
    List<TrackListCoverRow> findListCovers(@Param("ids") Collection<Long> ids);
}

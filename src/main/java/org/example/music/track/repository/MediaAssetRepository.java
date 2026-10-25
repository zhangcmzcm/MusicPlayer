package org.example.music.track.repository;

import java.util.Optional;
import org.example.music.track.domain.MediaAssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetRepository extends JpaRepository<MediaAssetEntity, Long> {

    Optional<MediaAssetEntity> findByBucketAndObjectKey(String bucket, String objectKey);
}

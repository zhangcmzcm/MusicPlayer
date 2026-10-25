package org.example.music.track.repository;

import java.util.UUID;
import org.example.music.track.domain.MediaImportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaImportRepository extends JpaRepository<MediaImportEntity, Long> {

    java.util.Optional<MediaImportEntity> findByUploadId(UUID uploadId);
}

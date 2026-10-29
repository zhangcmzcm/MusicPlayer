package org.example.music.track.repository;

import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrackRepository extends JpaRepository<TrackEntity, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TrackEntity t where t.id = :trackId and t.status = :status")
    int deleteArchived(@Param("trackId") Long trackId, @Param("status") TrackStatus status);
}
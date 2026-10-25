package org.example.music.track.repository;

import org.example.music.track.domain.TrackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackRepository extends JpaRepository<TrackEntity, Long> {
}

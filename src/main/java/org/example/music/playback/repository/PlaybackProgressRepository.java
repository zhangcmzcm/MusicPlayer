package org.example.music.playback.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.example.music.track.domain.TrackStatus;
import org.springframework.stereotype.Repository;

@Repository
public class PlaybackProgressRepository {
    private static final DateTimeFormatter UTC_DATETIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS", Locale.ROOT);
    private final EntityManager entityManager;

    public PlaybackProgressRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<PlaybackProgressTrackRow> lockTrackForShare(Long trackId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(
                        "select status,duration_ms from tracks where id=:trackId for share", Tuple.class)
                .setParameter("trackId", trackId)
                .getResultList();
        return rows.stream().findFirst().map(row -> new PlaybackProgressTrackRow(
                TrackStatus.valueOf((String) row.get("status")), number(row.get("duration_ms"))));
    }

    public void insertIfAbsent(Integer userId, String deviceId, Long trackId,
            long positionSeconds, long sequence, LocalDateTime recordedAt) {
        entityManager.createNativeQuery("""
                insert into user_playback_progress
                    (user_id,device_id,track_id,position_seconds,last_sequence,created_at,updated_at,created_by,updated_by)
                values (:userId,:deviceId,:trackId,:position,:sequence,
                    cast(:recordedAt as datetime(6)),cast(:recordedAt as datetime(6)),:userId,:userId)
                on duplicate key update user_id=user_playback_progress.user_id
                """)
                .setParameter("userId", userId)
                .setParameter("deviceId", deviceId)
                .setParameter("trackId", trackId)
                .setParameter("position", positionSeconds)
                .setParameter("sequence", sequence)
                .setParameter("recordedAt", recordedAt.format(UTC_DATETIME))
                .executeUpdate();
    }

    public PlaybackProgressRow lockProgress(Integer userId, String deviceId) {
        Tuple row = (Tuple) entityManager.createNativeQuery("""
                select device_id,track_id,position_seconds,last_sequence,
                    date_format(updated_at,'%Y-%m-%dT%H:%i:%s.%f') as saved_at
                from user_playback_progress
                where user_id=:userId and device_id=:deviceId for update
                """, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("deviceId", deviceId)
                .getSingleResult();
        return new PlaybackProgressRow((String) row.get("device_id"), number(row.get("track_id")),
                ((Number) row.get("position_seconds")).longValue(), ((Number) row.get("last_sequence")).longValue(),
                LocalDateTime.parse((String) row.get("saved_at")).toInstant(ZoneOffset.UTC));
    }

    public int updateIfSequenceOlder(Integer userId, String deviceId, Long trackId,
            long positionSeconds, long sequence, LocalDateTime recordedAt) {
        return entityManager.createNativeQuery("""
                update user_playback_progress
                set track_id=:trackId,position_seconds=:position,last_sequence=:sequence,
                    updated_at=cast(:recordedAt as datetime(6)),updated_by=:userId
                where user_id=:userId and device_id=:deviceId and last_sequence < :sequence
                """)
                .setParameter("userId", userId)
                .setParameter("deviceId", deviceId)
                .setParameter("trackId", trackId)
                .setParameter("position", positionSeconds)
                .setParameter("sequence", sequence)
                .setParameter("recordedAt", recordedAt.format(UTC_DATETIME))
                .executeUpdate();
    }

    private static Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}

package org.example.music.favorite.repository;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.example.music.track.domain.TrackStatus;
import org.springframework.stereotype.Repository;

@Repository
public class FavoriteRepository {
    private static final DateTimeFormatter UTC_DATETIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS", Locale.ROOT);
    private final EntityManager entityManager;

    public FavoriteRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<TrackStatus> lockTrackStatus(Long trackId) {
        List<?> statuses = entityManager.createNativeQuery("select status from tracks where id = :trackId for update", String.class)
                .setParameter("trackId", trackId)
                .getResultList();
        return statuses.isEmpty() ? Optional.empty() : Optional.of(TrackStatus.valueOf((String) statuses.getFirst()));
    }

    public void addIfAbsent(Integer userId, Long trackId, LocalDateTime createdAt) {
        entityManager.createNativeQuery("""
                insert into user_favorites(user_id,track_id,created_at,updated_at,created_by,updated_by)
                values(:userId,:trackId,cast(:createdAt as datetime(6)),cast(:createdAt as datetime(6)),:userId,:userId)
                on duplicate key update user_id = user_favorites.user_id
                """)
                .setParameter("userId", userId)
                .setParameter("trackId", trackId)
                // Binding UTC text avoids Hibernate's Timestamp/Calendar conversion for a DATETIME column.
                .setParameter("createdAt", createdAt.format(UTC_DATETIME))
                .executeUpdate();
    }
}

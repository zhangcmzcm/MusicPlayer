package org.example.music.favorite.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.example.music.favorite.service.FavoriteListSort;
import org.example.music.track.domain.TrackStatus;
import org.springframework.stereotype.Repository;

@Repository
public class FavoriteListRepository {
    private final EntityManager entityManager;

    public FavoriteListRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<FavoriteListRow> findRows(Integer userId, TrackStatus status, int offset, int size, FavoriteListSort sort) {
        var query = entityManager.createNativeQuery(contentSql(sort, status != null), Tuple.class)
                .setParameter("userId", userId).setParameter("offset", offset).setParameter("size", size);
        if (status != null) {
            query.setParameter("status", status.name());
        }
        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.stream().map(row -> new FavoriteListRow(number(row.get("id")), (String) row.get("title"),
                (String) row.get("artist_name"), (String) row.get("album_name"), number(row.get("duration_ms")),
                TrackStatus.valueOf((String) row.get("status")), number(row.get("cover_asset_id")),
                LocalDateTime.parse((String) row.get("favorited_at")).toInstant(ZoneOffset.UTC))).toList();
    }

    public long count(Integer userId, TrackStatus status) {
        var query = entityManager.createNativeQuery("select count(*) from user_favorites f straight_join tracks t on t.id=f.track_id"
                + " where f.user_id=:userId" + (status == null ? "" : " and t.status=:status"))
                .setParameter("userId", userId);
        if (status != null) {
            query.setParameter("status", status.name());
        }
        return ((Number) query.getSingleResult()).longValue();
    }

    public static String contentSql(FavoriteListSort sort, boolean filtered) {
        String column = switch (sort.property()) {
            case "id" -> "f.track_id";
            case "title" -> "t.title";
            case "artistName" -> "t.artist_name";
            case "createdAt" -> "t.created_at";
            case "favoritedAt" -> "f.created_at";
            default -> throw new IllegalArgumentException("Unsupported favorite sort");
        };
        String direction = sort.ascending() ? " asc" : " desc";
        // Drive from this owner's favorites, avoiding a catalog scan for sparse collections.
        // Return UTC wall time as text so JDBC/JVM timezone conversion cannot shift DATETIME(6).
        return "select t.id,t.title,t.artist_name,t.album_name,t.duration_ms,t.status,t.cover_asset_id,"
                + "date_format(f.created_at,'%Y-%m-%dT%H:%i:%s.%f') as favorited_at "
                + "from user_favorites f straight_join tracks t on t.id=f.track_id where f.user_id=:userId"
                + (filtered ? " and t.status=:status" : "") + " order by " + column + direction
                + (sort.property().equals("id") ? "" : ",f.track_id" + direction) + " limit :size offset :offset";
    }

    private Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}

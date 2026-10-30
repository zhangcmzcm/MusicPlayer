package org.example.music.track.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.List;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.service.TrackListSort;
import org.springframework.stereotype.Repository;

@Repository
public class TrackListRepository {
    private final EntityManager entityManager;

    public TrackListRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<TrackListRow> findRows(TrackStatus status, int offset, int size, TrackListSort sort) {
        var query = entityManager.createNativeQuery(contentSql(sort, status != null), Tuple.class)
                .setParameter("size", size).setParameter("offset", offset);
        if (status != null) {
            query.setParameter("status", status.name());
        }
        @SuppressWarnings("unchecked")
        List<Tuple> tuples = query.getResultList();
        return tuples.stream().map(row -> new TrackListRow(number(row.get("id")), (String) row.get("title"),
                (String) row.get("artist_name"), (String) row.get("album_name"), number(row.get("duration_ms")),
                TrackStatus.valueOf((String) row.get("status")), number(row.get("cover_asset_id")))).toList();
    }

    public static String contentSql(TrackListSort sort, boolean filtered) {
        String column = switch (sort.property()) {
            case "id" -> "id";
            case "title" -> "title";
            case "artistName" -> "artist_name";
            case "createdAt" -> "created_at";
            default -> throw new IllegalArgumentException("Unsupported track sort");
        };
        String direction = sort.ascending() ? " asc" : " desc";
        String innerOrder = "i." + column + direction + (column.equals("id") ? "" : ",i.id" + direction);
        String outerOrder = "t." + column + direction + (column.equals("id") ? "" : ",t.id" + direction);
        // LIMIT prevents derived-table merging: skip narrow index entries, fetch only this page's rows.
        return "select t.id,t.title,t.artist_name,t.album_name,t.duration_ms,t.status,t.cover_asset_id from tracks t join "
                + "(select i.id from tracks i" + (filtered ? " where i.status=:status" : "")
                + " order by " + innerOrder + " limit :size offset :offset) page_ids on page_ids.id=t.id order by " + outerOrder;
    }

    private Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    public long count(TrackStatus status) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(Long.class);
        var track = query.from(TrackEntity.class);
        query.select(cb.count(track));
        if (status != null) {
            query.where(cb.equal(track.get("status"), status));
        }
        return entityManager.createQuery(query).getSingleResult();
    }
}

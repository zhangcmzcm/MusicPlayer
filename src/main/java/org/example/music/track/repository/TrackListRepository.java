package org.example.music.track.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import java.util.ArrayList;
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
        return findRows(status, offset, size, sort, List.of(), 0);
    }

    public List<TrackListRow> findRows(TrackStatus status, int offset, int size, TrackListSort sort,
                                      List<String> searchTerms, int timeoutMs) {
        var query = entityManager.createNativeQuery(contentSql(sort, status != null, searchTerms.size(), timeoutMs), Tuple.class)
                .setParameter("size", size).setParameter("offset", offset);
        bindConditions(query, status, searchTerms);
        @SuppressWarnings("unchecked")
        List<Tuple> tuples = query.getResultList();
        return tuples.stream().map(row -> new TrackListRow(number(row.get("id")), (String) row.get("title"),
                (String) row.get("artist_name"), (String) row.get("album_name"), number(row.get("duration_ms")),
                TrackStatus.valueOf((String) row.get("status")), number(row.get("cover_asset_id")))).toList();
    }

    public static String contentSql(TrackListSort sort, boolean filtered) {
        return contentSql(sort, filtered, 0, 0);
    }

    public static String contentSql(TrackListSort sort, boolean filtered, int termCount, int timeoutMs) {
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
        // LIMIT keeps candidate pagination separate from the final row projection.
        return "select " + executionHint(termCount, timeoutMs)
                + "t.id,t.title,t.artist_name,t.album_name,t.duration_ms,t.status,t.cover_asset_id from tracks t join "
                + "(select i.id from tracks i" + conditions(filtered, termCount)
                + " order by " + innerOrder + " limit :size offset :offset) page_ids on page_ids.id=t.id order by " + outerOrder;
    }

    public static String countSql(boolean filtered, int termCount, int timeoutMs) {
        return "select " + executionHint(termCount, timeoutMs) + "count(*) from tracks i" + conditions(filtered, termCount);
    }

    private static String executionHint(int termCount, int timeoutMs) {
        if (termCount == 0) {
            return "";
        }
        if (timeoutMs < 1) {
            throw new IllegalArgumentException("Search execution budget must be positive");
        }
        return "/*+ MAX_EXECUTION_TIME(" + timeoutMs + ") */ ";
    }

    private static String conditions(boolean filtered, int termCount) {
        if (termCount < 0 || termCount > 5) {
            throw new IllegalArgumentException("Invalid search term count");
        }
        var predicates = new ArrayList<String>();
        if (filtered) {
            predicates.add("i.status=:status");
        }
        for (int index = 0; index < termCount; index++) {
            String parameter = ":q" + index;
            predicates.add("(i.title like " + parameter + " escape '!' or i.artist_name like " + parameter
                    + " escape '!' or i.album_name like " + parameter + " escape '!')");
        }
        return predicates.isEmpty() ? "" : " where " + String.join(" and ", predicates);
    }

    private void bindConditions(Query query, TrackStatus status, List<String> terms) {
        if (status != null) {
            query.setParameter("status", status.name());
        }
        for (int index = 0; index < terms.size(); index++) {
            String escaped = terms.get(index).replace("!", "!!").replace("%", "!%").replace("_", "!_");
            query.setParameter("q" + index, "%" + escaped + "%");
        }
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

    public long count(TrackStatus status, List<String> searchTerms, int timeoutMs) {
        var query = entityManager.createNativeQuery(countSql(status != null, searchTerms.size(), timeoutMs));
        bindConditions(query, status, searchTerms);
        return ((Number) query.getSingleResult()).longValue();
    }
}

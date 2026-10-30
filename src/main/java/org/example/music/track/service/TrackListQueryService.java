package org.example.music.track.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.example.music.common.dto.PageResponse;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.dto.TrackListItemResponse;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackListCoverRow;
import org.example.music.track.repository.TrackListRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackListQueryService {
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private final TrackListRepository tracks;
    private final MediaAssetRepository assets;

    public TrackListQueryService(TrackListRepository tracks, MediaAssetRepository assets) {
        this.tracks = tracks;
        this.assets = assets;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<TrackListItemResponse> query(TrackListParameters parameters, TrackStatus status) {
        var rows = tracks.findRows(status, (int) ((long) parameters.page() * parameters.size()), parameters.size(), parameters.sort());
        long total = tracks.count(status);
        var ids = rows.stream().map(row -> row.coverAssetId()).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, TrackListCoverRow> covers = ids.isEmpty() ? Map.of() : assets.findListCovers(ids).stream()
                .collect(Collectors.toMap(TrackListCoverRow::id, Function.identity()));
        List<TrackListItemResponse> items = rows.stream().map(row -> {
            TrackListItemResponse.Cover cover = null;
            if (row.coverAssetId() != null) {
                var asset = covers.get(row.coverAssetId());
                validate(asset);
                cover = new TrackListItemResponse.Cover("/api/v1/tracks/" + row.id() + "/cover", asset.contentType(), asset.sizeBytes());
            }
            return new TrackListItemResponse(row.id(), row.title(), row.artistName(), row.albumName(),
                    row.durationMs(), row.status().name(), cover);
        }).toList();
        long totalPages = total / parameters.size() + (total % parameters.size() == 0 ? 0 : 1);
        return new PageResponse<>(items, parameters.page(), parameters.size(), total, totalPages,
                (long) parameters.page() + 1 < totalPages);
    }

    private void validate(TrackListCoverRow asset) {
        if (asset == null || asset.kind() != MediaAssetKind.COVER || asset.contentType() == null
                || !TYPES.contains(asset.contentType()) || asset.sizeBytes() == null || asset.sizeBytes() <= 0
                || asset.sha256() == null || !asset.sha256().matches("[0-9a-fA-F]{64}")
                || asset.bucket() == null || asset.bucket().isBlank() || asset.objectKey() == null || asset.objectKey().isBlank()) {
            throw new IllegalStateException("Track list cover asset is inconsistent");
        }
    }
}

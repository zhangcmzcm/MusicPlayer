package org.example.music.track.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "歌曲归档结果")
public record TrackArchiveResponse(
        @Schema(description = "歌曲 ID", example = "42")
        Long id,

        @Schema(description = "归档后的歌曲状态", example = "ARCHIVED")
        String status,

        @Schema(description = "归档后的乐观锁版本号", example = "5")
        Long version,

        @Schema(description = "最后更新时间，UTC ISO-8601", example = "2026-10-29T10:30:00Z")
        Instant updatedAt
) {
}
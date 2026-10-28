package org.example.music.track.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "歌曲发布结果")
public record TrackPublishResponse(
        @Schema(description = "歌曲 ID", example = "42")
        Long id,

        @Schema(description = "发布后的歌曲状态", example = "PUBLISHED")
        String status,

        @Schema(description = "发布后的乐观锁版本号", example = "4")
        Long version,

        @Schema(description = "最后更新时间，UTC ISO-8601", example = "2026-10-27T13:20:30Z")
        Instant updatedAt
) {
}

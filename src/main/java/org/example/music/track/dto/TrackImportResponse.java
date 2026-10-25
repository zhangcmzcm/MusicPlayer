package org.example.music.track.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "歌曲导入响应")
public record TrackImportResponse(
        @Schema(description = "媒体导入 ID", example = "18")
        Long importId,

        @Schema(description = "导入幂等键")
        UUID uploadId,

        @Schema(description = "导入状态", example = "READY")
        String status,

        @Schema(description = "导入生成的歌曲草稿")
        ImportedTrackResponse track,

        @Schema(description = "元数据提取警告")
        List<String> metadataWarnings
) {
}

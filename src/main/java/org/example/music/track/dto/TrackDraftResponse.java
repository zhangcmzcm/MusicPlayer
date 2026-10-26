package org.example.music.track.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "歌曲草稿信息")
public record TrackDraftResponse(
        @Schema(description = "歌曲 ID", example = "42")
        Long id,

        @Schema(description = "歌曲标题")
        String title,

        @Schema(description = "主艺术家名称")
        String artistName,

        @Schema(description = "专辑名称")
        String albumName,

        @Schema(description = "歌曲时长，单位毫秒", example = "215430")
        Long durationMs,

        @Schema(description = "曲序")
        Integer trackNumber,

        @Schema(description = "碟片序号")
        Integer discNumber,

        @Schema(description = "当前版本号", example = "4")
        Long version,

        @Schema(description = "歌曲状态", example = "DRAFT")
        String status,

        @Schema(description = "音频媒体资产")
        MediaAssetResponse audio,

        @Schema(description = "可选封面媒体资产")
        MediaAssetResponse cover
) {
}

package org.example.music.track.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "歌曲详情，可选字段缺失时返回 null")
public record TrackDetailResponse(
        @Schema(description = "歌曲 ID", example = "42") Long id,
        @Schema(description = "歌曲标题") String title,
        @Schema(description = "艺术家") String artistName,
        @Schema(description = "专辑名称，可空") String albumName,
        @Schema(description = "时长，毫秒，可空") Long durationMs,
        @Schema(description = "曲序，可空") Integer trackNumber,
        @Schema(description = "碟号，可空") Integer discNumber,
        @Schema(description = "状态", allowableValues = {"DRAFT", "PUBLISHED", "ARCHIVED"}) String status,
        @Schema(description = "封面元信息，无封面时为 null") Cover cover
) {
    @Schema(description = "封面元信息")
    public record Cover(
            @Schema(description = "同源封面地址，读取接口后续实现", example = "/api/v1/tracks/42/cover") String url,
            @Schema(description = "图片内容类型", example = "image/jpeg") String contentType,
            @Schema(description = "大小，字节") Long sizeBytes
    ) {
    }
}

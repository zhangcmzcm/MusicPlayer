package org.example.music.track.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "歌曲列表项")
public record TrackListItemResponse(
        @Schema(description = "歌曲 ID") Long id,
        @Schema(description = "标题") String title,
        @Schema(description = "艺术家") String artistName,
        @Schema(description = "专辑，可空") String albumName,
        @Schema(description = "毫秒时长，可空") Long durationMs,
        @Schema(description = "歌曲状态", allowableValues = {"DRAFT", "PUBLISHED", "ARCHIVED"}) String status,
        @Schema(description = "封面元信息，无封面时为 null") Cover cover
) {
    @Schema(description = "封面元信息，不验证对象存储存在性")
    public record Cover(
            @Schema(description = "需鉴权的同源图片地址") String url,
            @Schema(description = "图片 MIME") String contentType,
            @Schema(description = "封面字节数") Long sizeBytes
    ) {
    }
}

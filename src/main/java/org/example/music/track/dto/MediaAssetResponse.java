package org.example.music.track.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "媒体资产信息")
public record MediaAssetResponse(
        @Schema(description = "媒体资产 ID", example = "51")
        Long id,

        @Schema(description = "MIME 内容类型", example = "audio/mpeg")
        String contentType,

        @Schema(description = "对象大小，单位字节", example = "8230144")
        long sizeBytes,

        @Schema(description = "SHA-256 十六进制摘要")
        String sha256
) {
}

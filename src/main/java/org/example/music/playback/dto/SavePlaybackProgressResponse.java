package org.example.music.playback.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "已持久化的设备播放状态")
public record SavePlaybackProgressResponse(
        @Schema(description = "规范化为小写的设备UUID", format = "uuid") String deviceId,
        @Schema(description = "歌曲ID", format = "int64") Long trackId,
        @Schema(description = "整秒位置", minimum = "0") long positionSeconds,
        @Schema(description = "已保存事件序号", minimum = "1", maximum = "9007199254740991") long sequence,
        @Schema(description = "最近新事件保存时间，UTC，微秒精度") Instant updatedAt
) {
}

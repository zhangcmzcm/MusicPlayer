package org.example.music.playback.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "恰好三个整数字段组成的设备最近播放状态，拒绝小数、科学计数法及数字字符串",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SavePlaybackProgressRequest(
        @NotNull @Positive
        @Schema(description = "当前歌曲正整数ID", minimum = "1", maximum = "9223372036854775807", requiredMode = Schema.RequiredMode.REQUIRED)
        Long trackId,
        @NotNull @PositiveOrZero
        @Schema(description = "非负整秒位置，有效时长已知时不得超过总时长向下取整", minimum = "0", maximum = "9223372036854775807", requiredMode = Schema.RequiredMode.REQUIRED)
        Long positionSeconds,
        @NotNull @Min(1) @Max(9007199254740991L)
        @Schema(description = "当前账号与设备的持久递增事件序号", minimum = "1", maximum = "9007199254740991", requiredMode = Schema.RequiredMode.REQUIRED)
        Long sequence
) {
}

package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "管理员身份选择票据")
public record RoleSelectionResponse(
        @Schema(description = "一次性短时登录票据")
        String loginTicket,

        @Schema(description = "票据过期时间")
        Instant expiresAt
) {
}
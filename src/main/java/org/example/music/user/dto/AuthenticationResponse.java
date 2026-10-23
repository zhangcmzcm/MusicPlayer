package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "JWT 登录结果")
public record AuthenticationResponse(
        @Schema(description = "Bearer JWT")
        String accessToken,

        @Schema(example = "Bearer")
        String tokenType,

        @Schema(description = "JWT 过期时间")
        Instant expiresAt,

        @Schema(description = "登录用户")
        LoginUserResponse user
) {
}
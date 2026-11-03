package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "本人再次验证成功，新Token保留原登录角色及验证版本")
public record ReauthenticationResponse(
        @Schema(description = "新访问Token") String accessToken,
        @Schema(description = "固定Bearer", allowableValues = "Bearer") String tokenType,
        @Schema(description = "访问Token的UTC到期时间") Instant expiresAt,
        @Schema(description = "凭据验证完成时间，与JWT整数秒auth_time一致") Instant authenticatedAt
) {
    @Override public String toString() { return "ReauthenticationResponse[accessToken=<redacted>, expiresAt=" + expiresAt + ", authenticatedAt=" + authenticatedAt + "]"; }
}

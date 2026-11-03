package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "已发送并激活的本人身份验证邮件请求")
public record ReauthenticationEmailRequestResponse(
        @Schema(description = "32随机字节的Base64URL无填充请求标识", minLength = 43, maxLength = 43) String challengeId,
        @Schema(description = "由Redis准入时间起算的UTC绝对到期时间") Instant expiresAt
) {
    @Override public String toString() { return "ReauthenticationEmailRequestResponse[challengeId=<redacted>, expiresAt=" + expiresAt + "]"; }
}

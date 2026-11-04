package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "已激活的邮箱变更申请")
public record EmailChangeRequestResponse(
        @Schema(description = "随机申请标识，后续确认时使用") String challengeId,
        @Schema(description = "从获准发送起算5分钟的UTC到期时间") Instant expiresAt) {
    @Override public String toString() { return "EmailChangeRequestResponse[challengeId=<redacted>, expiresAt=" + expiresAt + "]"; }
}

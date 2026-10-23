package org.example.music.email.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.example.music.verification.domain.VerificationCodePurpose;

@Schema(description = "邮箱验证码发送成功响应")
public record EmailSendCodeResponse(
        @Schema(description = "验证码用途", example = "REGISTER")
        VerificationCodePurpose purpose,

        @Schema(description = "验证码过期时间，UTC ISO-8601", example = "2026-10-22T12:40:00Z")
        Instant expiresAt
) {
}
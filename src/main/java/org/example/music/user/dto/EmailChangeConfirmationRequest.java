package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "确认本人邮箱变更")
public record EmailChangeConfirmationRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}")
        @Schema(description = "发码返回的规范32字节Base64URL申请标识", minLength = 43, maxLength = 43) String challengeId,
        @NotBlank @Pattern(regexp = "[0-9]{6}")
        @Schema(description = "新邮箱收到的六位ASCII数字，保留前导零，不trim", minLength = 6, maxLength = 6) String code) {
    @Override public String toString() { return "EmailChangeConfirmationRequest[redacted]"; }
}

package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(description = "邮箱变更验证码申请")
public record EmailChangeRequest(
        @NotBlank @Email @Size(max = 120)
        @Schema(description = "新邮箱；去除首尾空白并转小写后校验，最多120字符", example = "new@example.com", maxLength = 120)
        String newEmail) {
    @Override public String toString() { return "EmailChangeRequest[newEmail=<redacted>]"; }
}

package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(description = "找回密码验证码申请")
public record PasswordResetRequest(
        @NotBlank @Email @Size(max = 120)
        @Schema(description = "绑定邮箱；去除首尾空白并转小写后校验，最多120字符", example = "new@example.com", maxLength = 120)
        String email) {
    @Override public String toString() { return "PasswordResetRequest[email=<redacted>]"; }
}

package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

public record PasswordResetVerificationRequest(
        @NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}")
        @Schema(description="规范Base64URL申请标识，解码32字节",minLength=43,maxLength=43) String challengeId,
        @NotNull @Pattern(regexp="[0-9]{6}")
        @Schema(accessMode=Schema.AccessMode.WRITE_ONLY,description="六位ASCII邮箱验证码",minLength=6,maxLength=6) String code) {
    @Override public String toString() { return "PasswordResetVerificationRequest[redacted]"; }
}

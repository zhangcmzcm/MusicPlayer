package org.example.music.email.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.example.music.verification.domain.VerificationCodePurpose;

@Schema(description = "发送邮箱验证码请求")
public record EmailSendCodeRequest(
        @NotBlank
        @Email
        @Size(max = 120)
        @Schema(description = "接收验证码的邮箱", example = "alice@example.com", maxLength = 120)
        String email,

        @NotNull
        @Schema(description = "验证码用途", example = "REGISTER")
        VerificationCodePurpose purpose
) {
}
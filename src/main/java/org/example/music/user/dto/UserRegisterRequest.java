package org.example.music.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "用户注册请求")
public record UserRegisterRequest(
        @NotBlank
        @Size(min = 3, max = 20)
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "must contain only letters, digits, and underscores")
        @Schema(description = "用户名", example = "alice_2026", maxLength = 20)
        String username,

        @NotBlank
        @Size(min = 8, max = 128)
        @Schema(description = "明文密码，仅通过 HTTPS 传输", example = "correct-password", minLength = 8, maxLength = 128)
        String password,

        @Email
        @Size(max = 120)
        @Schema(description = "可选邮箱", example = "alice@example.com", maxLength = 120)
        String email,

        @Pattern(regexp = "^\\d{6}$", message = "must contain exactly six digits")
        @Schema(description = "邮箱验证码；提供邮箱时必填", example = "123456")
        String code
) {

    @JsonIgnore
    @AssertTrue(message = "code is required when email is present and is not allowed otherwise")
    public boolean isEmailCodePairValid() {
        boolean hasEmail = email != null && !email.isBlank();
        boolean hasCode = code != null && !code.isBlank();
        return hasEmail == hasCode;
    }
}

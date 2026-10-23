package org.example.music.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.example.music.user.domain.UserRole;

@Schema(description = "用户登录请求")
public record LoginRequest(
        @NotNull
        @Schema(description = "登录方式", example = "USERNAME_PASSWORD")
        LoginType loginType,

        @Size(min = 3, max = 120)
        @Schema(description = "用户名或邮箱", example = "alice")
        String account,

        @Size(min = 8, max = 128)
        @Schema(description = "明文密码", example = "correct-password")
        String password,

        @Size(max = 120)
        @Schema(description = "邮箱验证码登录邮箱", example = "alice@example.com")
        String email,

        @Pattern(regexp = "^\\d{6}$")
        @Schema(description = "登录验证码", example = "123456")
        String code,

        @Size(max = 256)
        @Schema(description = "管理员身份选择票据")
        String loginTicket,

        @Schema(description = "登录身份，仅用于管理员身份选择", example = "USER")
        UserRole loginRole
) {

    @JsonIgnore
    @AssertTrue(message = "login fields do not match the selected login type")
    public boolean isLoginFieldsValid() {
        if (loginType == null) {
            return false;
        }
        return switch (loginType) {
            case USERNAME_PASSWORD -> hasValue(account) && hasValue(password) && !account.contains("@")
                    && account.matches("^[A-Za-z0-9_]{3,80}$")
                    && hasValue(password) && password.length() >= 8 && password.length() <= 128;
            case EMAIL_PASSWORD -> hasValue(account) && hasValue(password)
                    && isEmail(account) && password.length() >= 8 && password.length() <= 128;
            case EMAIL_CODE -> hasValue(email) && hasValue(code) && isEmail(email) && code.matches("^\\d{6}$");
            case ROLE_SELECTION -> hasValue(loginTicket) && loginRole != null;
        };
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isEmail(String value) {
        return value != null && value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") && value.length() <= 120;
    }
}
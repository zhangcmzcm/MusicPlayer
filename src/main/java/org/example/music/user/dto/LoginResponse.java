package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "登录响应")
public record LoginResponse(
        @Schema(description = "登录状态")
        LoginStatus status,

        @Schema(description = "登录成功结果")
        AuthenticationResponse authentication,

        @Schema(description = "管理员身份选择结果")
        RoleSelectionResponse roleSelection
) {

    public static LoginResponse authenticated(AuthenticationResponse authentication) {
        return new LoginResponse(LoginStatus.AUTHENTICATED, authentication, null);
    }

    public static LoginResponse roleSelectionRequired(RoleSelectionResponse roleSelection) {
        return new LoginResponse(LoginStatus.ROLE_SELECTION_REQUIRED, null, roleSelection);
    }
}
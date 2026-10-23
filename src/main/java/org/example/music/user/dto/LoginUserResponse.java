package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.music.user.domain.UserRole;

@Schema(description = "登录用户信息")
public record LoginUserResponse(
        @Schema(example = "42")
        Integer id,

        @Schema(example = "alice")
        String username,

        @Schema(example = "alice@example.com")
        String email,

        @Schema(description = "本次实际登录身份", example = "USER")
        UserRole role
) {
}
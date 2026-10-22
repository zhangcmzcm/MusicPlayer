package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.example.music.user.domain.UserRole;

@Schema(description = "注册成功的用户信息")
public record UserRegisterResponse(
        @Schema(description = "用户 ID", example = "42")
        Integer id,

        @Schema(description = "用户名", example = "alice_2026")
        String username,

        @Schema(description = "邮箱", example = "alice@example.com")
        String email,

        @Schema(description = "用户角色", example = "USER")
        UserRole userRole,

        @Schema(description = "创建时间，UTC ISO-8601", example = "2026-10-21T12:34:56Z")
        Instant createdAt
) {
}

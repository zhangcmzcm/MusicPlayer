package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.music.user.domain.UserRole;

@Schema(description = "用户角色更新结果")
public record UserRoleUpdateResponse(
        @Schema(example = "42")
        Integer id,

        @Schema(example = "target_user")
        String username,

        @Schema(description = "最新角色", example = "ADMIN")
        UserRole role
) {
}
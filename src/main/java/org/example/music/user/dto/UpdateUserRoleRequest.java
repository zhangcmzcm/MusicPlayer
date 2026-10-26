package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.example.music.user.domain.UserRole;

@Schema(description = "管理员修改用户角色请求")
public record UpdateUserRoleRequest(
        @NotNull
        @Schema(description = "目标角色", example = "ADMIN")
        UserRole role
) {
}
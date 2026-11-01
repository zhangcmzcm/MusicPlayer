package org.example.music.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.example.music.user.domain.UserRole;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "当前登录用户的账户资料")
public record CurrentUserResponse(
        @Schema(description = "本人用户 ID", example = "42") Integer id,
        @Schema(description = "当前用户名", example = "alice") String username,
        @Schema(description = "本人完整邮箱；历史记录允许为空", example = "alice@example.com", nullable = true) String email,
        @Schema(description = "数据库账户角色", example = "ADMIN") UserRole userRole,
        @Schema(description = "本次已验证的登录身份", example = "USER") UserRole loginRole,
        @Schema(description = "账户创建时间，UTC ISO-8601；历史记录允许为空", example = "2026-10-21T12:34:56Z", nullable = true) Instant createdAt
) {
}

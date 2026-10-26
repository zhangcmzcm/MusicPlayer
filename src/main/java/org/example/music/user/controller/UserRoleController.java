package org.example.music.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.UpdateUserRoleRequest;
import org.example.music.user.dto.UserRoleUpdateResponse;
import org.example.music.user.service.UserRoleAuthorizationService;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "用户管理接口")
public class UserRoleController {

    private final UserRoleAuthorizationService userRoleAuthorizationService;

    public UserRoleController(UserRoleAuthorizationService userRoleAuthorizationService) {
        this.userRoleAuthorizationService = userRoleAuthorizationService;
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "修改用户角色", description = "管理员为其他用户授权 USER 或 ADMIN 角色")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "角色更新成功", content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = UserRoleUpdateResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "未认证或 Token 已失效", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "无权限或尝试修改自己", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "目标用户不存在", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "认证状态存储不可用", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<UserRoleUpdateResponse> updateRole(
            @PathVariable("id") Integer targetUserId,
            @Valid @RequestBody UpdateUserRoleRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Integer actorUserId = Integer.valueOf(jwt.getSubject());
        UserRole actorRole = UserRole.valueOf(String.valueOf(jwt.getClaims().get("role")));
        return ResponseEntity.ok(userRoleAuthorizationService.updateRole(
                actorUserId,
                actorRole,
                targetUserId,
                request.role()
        ));
    }
}
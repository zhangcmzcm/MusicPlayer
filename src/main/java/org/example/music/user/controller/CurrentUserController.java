package org.example.music.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.dto.CurrentUserResponse;
import org.example.music.user.service.CurrentUserService;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "用户管理接口")
public class CurrentUserController {
    private final CurrentUserService service;

    public CurrentUserController(CurrentUserService service) {
        this.service = service;
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息", description = "仅从已验证的认证主体确定本人；返回账户角色和本次登录身份，不接受前端用户 ID 或角色作为身份来源。未知查询参数忽略。")
    @ApiResponse(responseCode = "200", description = "本人账户资料；邮箱和创建时间可为 null", headers = @Header(name = "Cache-Control", description = "no-store"), content = @Content(mediaType = "application/json", schema = @Schema(implementation = CurrentUserResponse.class)))
    @ApiResponse(responseCode = "401", description = "未认证、声明无效或 Token 已撤销；JWT 签名错误与过期沿用资源服务器现有响应，可能无 ProblemDetail 响应体", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "AUTH_STATE_STORE_UNAVAILABLE 或 USER_PROFILE_STORE_UNAVAILABLE", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<CurrentUserResponse> getCurrentUser(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.getCurrentUser(actor));
    }
}

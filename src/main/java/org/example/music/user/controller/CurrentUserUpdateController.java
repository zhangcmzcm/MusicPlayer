package org.example.music.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.dto.CurrentUserResponse;
import org.example.music.user.dto.UpdateCurrentUserRequest;
import org.example.music.user.service.CurrentUserUpdateService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "用户管理接口")
public class CurrentUserUpdateController {
    private final CurrentUserUpdateService service;

    public CurrentUserUpdateController(CurrentUserUpdateService service) {
        this.service = service;
    }

    @PatchMapping(value = "/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "修改本人用户名", description = "身份仅来自认证主体；请求仅允许一个字符串 username，拒绝未知字段和重复键。保留大小写，不修改邮箱、密码或角色，不撤销 Token。相同值不更新时间，并发按数据库执行顺序生效。")
    @ApiResponse(responseCode = "200", description = "本人资料，完全相同用户名也成功", headers = @Header(name = "Cache-Control", description = "no-store"), content = @Content(mediaType = "application/json", schema = @Schema(implementation = CurrentUserResponse.class)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED；字段、类型、重复键、JSON 或用户名格式非法", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "未认证、声明非法或 TOKEN_REVOKED；JWT 签名错误与过期沿用资源服务器响应，可能没有 ProblemDetail 响应体", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "USERNAME_ALREADY_EXISTS，包含并发抢占", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "AUTH_STATE_STORE_UNAVAILABLE 或 USER_PROFILE_UPDATE_UNAVAILABLE；提交确认失败时修改可能已生效，可重新读取确认", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<CurrentUserResponse> updateCurrentUser(
            @Valid @RequestBody UpdateCurrentUserRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.updateCurrentUser(actor, request));
    }
}

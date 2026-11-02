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
import org.example.music.user.dto.ChangePasswordRequest;
import org.example.music.user.service.CurrentUserPasswordService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "用户管理接口")
public class CurrentUserPasswordController {
    private final CurrentUserPasswordService service;

    public CurrentUserPasswordController(CurrentUserPasswordService service) {
        this.service = service;
    }

    @PutMapping(value = "/me/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "修改本人密码", description = "USER/ADMIN 登录本人验证原密码后设置新密码；身份仅取认证主体。仅接受 oldPassword/newPassword，拒绝重复键及未知字段。每人900秒最多5次有效格式尝试，Redis不可用则拒绝继续。提交时递增版本，当前和其他登录旧Token全部撤销；不签发新Token，需重新登录。")
    @ApiResponse(responseCode = "204", description = "修改成功，无响应体；当前及其他旧Token撤销", headers = @Header(name = "Cache-Control", description = "no-store"), content = @Content)
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED、OLD_PASSWORD_INCORRECT、NEW_PASSWORD_SAME_AS_OLD", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED、INVALID_TOKEN 或 TOKEN_REVOKED；JWT签名错误/过期沿用资源服务器响应，可能无ProblemDetail", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "PASSWORD_CHANGE_CONFLICT，不自动重试", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "PASSWORD_CHANGE_RATE_LIMITED", headers = @Header(name = "Retry-After", description = "等待秒数，向上取整且至少1"), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "AUTH_STATE_STORE_UNAVAILABLE、PASSWORD_CHANGE_RATE_LIMIT_UNAVAILABLE 或 PASSWORD_CHANGE_STORE_UNAVAILABLE；提交确认故障可能已生效，应通过登录确认", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
        service.changePassword(actor, request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}

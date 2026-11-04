package org.example.music.user.controller;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.example.music.common.web.ClientIpResolver;
import org.example.music.security.*;
import org.example.music.user.dto.*;
import org.example.music.user.exception.InvalidTokenException;
import org.example.music.user.service.EmailChangeRequestService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Users", description = "用户管理接口")
public class EmailChangeRequestController {
    private final EmailChangeRequestService service;
    private final EmailChangeRequestReader reader;
    private final ClientIpResolver clientIpResolver;

    public EmailChangeRequestController(EmailChangeRequestService service, EmailChangeRequestReader reader, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.reader = reader;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping(value = "/api/v1/users/me/email-change-requests", produces = "application/json")
    @Operation(summary = "向新邮箱发送变更验证码", description = "USER/ADMIN本人有效JWT且真实auth_time距今不超过300秒（含端点）。先检查近期资格，再严格解析唯一newEmail字段，UTF-8 JSON正文最多16 KiB，规范化后邮箱最长120字符。允许未绑定用户首次绑定；不修改邮箱或登录状态，不预占邮箱。每人最多一个有效申请，成功发送并复核、激活后替换旧申请。用户及目标邮箱各60秒一次/UTC日10次，连接IP固定60秒5次；准入后失败不退额度。期限从Redis准入起算300秒；SMTP接受不保证送达，激活确认丢失503时可能已替换旧申请。后续确认接口另行实现。",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = EmailChangeRequest.class))))
    @ApiResponse(responseCode = "201", description = "邮件被接受且申请激活", headers = @Header(name = "Cache-Control", description = "no-store"), content = @Content(mediaType = "application/json", schema = @Schema(implementation = EmailChangeRequestResponse.class)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED：正文结构、编码、邮箱或16 KiB上限不合法", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "REAUTHENTICATION_REQUIRED：缺auth_time或超窗；UNAUTHORIZED/INVALID_TOKEN/TOKEN_REVOKED沿用认证现状，JWT签名错误/过期可能无ProblemDetail", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN：现有安全链拒绝访问", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "EMAIL_UNCHANGED、EMAIL_ALREADY_EXISTS、EMAIL_CHANGE_REQUEST_CONFLICT", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE：需要UTF-8 application/json，缺失或非法Content-Type同样拒绝", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "EMAIL_CHANGE_RATE_LIMITED", headers = @Header(name = "Retry-After", description = "全部阻塞额度最大等待秒数，向上取整且至少1"), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "EMAIL_SEND_FAILED：SMTP失败或20秒等待超时，可能收到无效邮件", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "EMAIL_CHANGE_REQUEST_UNAVAILABLE或AUTH_STATE_STORE_UNAVAILABLE：存储故障/状态损坏/发送满载/激活结果未知", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<EmailChangeRequestResponse> request(HttpServletRequest request, @Parameter(hidden = true) Authentication authentication) {
        var session = AuthenticatedSessionContext.from(authentication).orElseThrow(InvalidTokenException::new);
        var actor = (AuthenticatedUserPrincipal) authentication.getPrincipal();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(service.request(actor, session, () -> reader.read(request), clientIpResolver.resolve(request)));
    }
}

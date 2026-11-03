package org.example.music.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.example.music.common.web.ClientIpResolver;
import org.example.music.security.AuthenticatedSessionContext;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.dto.ReauthenticationEmailRequestResponse;
import org.example.music.user.exception.InvalidTokenException;
import org.example.music.user.exception.ReauthenticationEmailInvalidRequestException;
import org.example.music.user.service.ReauthenticationEmailRequestService;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Users", description = "用户管理接口")
public class ReauthenticationEmailRequestController {
    private final ReauthenticationEmailRequestService service;
    private final ClientIpResolver clientIpResolver;

    public ReauthenticationEmailRequestController(ReauthenticationEmailRequestService service, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping(value = "/api/v1/users/me/reauthentication-email-requests", produces = "application/json")
    @Operation(summary = "申请本人再次验证身份邮件", description = "USER/ADMIN普通有效JWT即可，无需近期auth_time，兼容缺auth_time的旧Token。只向数据库中的本人当前邮箱发送专用验证码；必须零字节正文，不能指定用户或邮箱。发送被SMTP接受且绑定复核通过后激活请求，不修改邮箱、认证时间或版本，不签Token。有效期从Redis准入时间起算300秒；发送接受不代表送达，失败或超时可能仍收到无效邮件，额度不退，不自动重试。后续兑换接口尚未实现。")
    @ApiResponse(responseCode = "201", description = "邮件已被接受且请求激活", headers = @Header(name = "Cache-Control", description = "no-store"), content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReauthenticationEmailRequestResponse.class)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED：任何非空正文均拒绝", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED、INVALID_TOKEN、TOKEN_REVOKED；JWT签名错误/过期沿用资源服务器现状，可能无ProblemDetail", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "CURRENT_EMAIL_NOT_BOUND、REAUTH_EMAIL_REQUEST_CONFLICT", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "REAUTH_EMAIL_RATE_LIMITED：用户60秒、UTC日10次、连接IP固定60秒5次", headers = @Header(name = "Retry-After", description = "各阻塞桶最长等待秒数，向上取整且至少1"), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "EMAIL_SEND_FAILED：发送失败或20秒等待超时", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "AUTH_STATE_STORE_UNAVAILABLE、REAUTH_EMAIL_REQUEST_UNAVAILABLE；数据库/Redis故障、状态损坏或发送器满载；激活确认丢失时可能已生效", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ReauthenticationEmailRequestResponse> request(HttpServletRequest request,
            @Parameter(hidden = true) Authentication authentication) {
        try {
            // 不依赖Content-Length；分块/未知长度同样只读取首字节检查。
            if (request.getInputStream().read() != -1) throw new ReauthenticationEmailInvalidRequestException();
        } catch (IOException failure) { throw new ReauthenticationEmailInvalidRequestException(); }
        var context = AuthenticatedSessionContext.from(authentication).orElseThrow(InvalidTokenException::new);
        var actor = (AuthenticatedUserPrincipal) authentication.getPrincipal();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(service.request(actor, context, clientIpResolver.resolve(request)));
    }
}

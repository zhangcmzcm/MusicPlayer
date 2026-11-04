package org.example.music.user.controller;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.example.music.security.*;
import org.example.music.user.dto.*;
import org.example.music.user.exception.InvalidTokenException;
import org.example.music.user.service.EmailChangeConfirmationService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Users", description = "用户管理接口")
public class EmailChangeConfirmationController {
    private final EmailChangeConfirmationService service;
    private final EmailChangeConfirmationRequestReader reader;

    public EmailChangeConfirmationController(EmailChangeConfirmationService service, EmailChangeConfirmationRequestReader reader) {
        this.service = service;
        this.reader = reader;
    }

    @PutMapping("/api/v1/users/me/email")
    @Operation(summary = "确认本人邮箱变更", description = "USER/ADMIN本人有效JWT且真实auth_time距准入不超过300秒（含端点）。严格challengeId/code两字符串、UTF-8 JSON正文最多16 KiB。每用户独立900秒最多10次，获准后失败不退额；第五次错码失效，正确码一次性消费。Redis明确消费后才用原始邮箱/角色/认证版本条件更新，数据库提交后204并撤销全部旧登录，不签新Token。消费后失败不恢复；503提交未知时邮箱可能已更新，请恢复有效登录核实。新申请先激活则旧码失效，旧消费先发生可继续条件更新。",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = EmailChangeConfirmationRequest.class))))
    @ApiResponse(responseCode = "204", description = "邮箱及版本已提交，全部旧登录失效", headers = @Header(name = "Cache-Control", description = "no-store"))
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED：正文结构、编码、标识或16 KiB上限不合法；EMAIL_CHANGE_VERIFICATION_FAILED：码或申请失效", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "REAUTHENTICATION_REQUIRED：缺auth_time或超窗；UNAUTHORIZED/INVALID_TOKEN/TOKEN_REVOKED沿用认证现状，JWT签名错误/过期可能无ProblemDetail", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN：现有安全链拒绝访问", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "EMAIL_CHANGE_CONFLICT、EMAIL_ALREADY_EXISTS", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE：需要UTF-8 application/json，缺失或非法Content-Type同样拒绝", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "EMAIL_CHANGE_CONFIRMATION_RATE_LIMITED", headers = @Header(name = "Retry-After", description = "独立900秒窗口剩余秒数，向上取整且至少1"), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "EMAIL_CHANGE_CONFIRMATION_UNAVAILABLE或AUTH_STATE_STORE_UNAVAILABLE：存储故障、状态损坏、版本无法递增或提交结果未知", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> confirm(HttpServletRequest request, @Parameter(hidden = true) Authentication authentication) {
        var session = AuthenticatedSessionContext.from(authentication).orElseThrow(InvalidTokenException::new);
        var actor = (AuthenticatedUserPrincipal) authentication.getPrincipal();
        service.confirm(actor, session, () -> reader.read(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}

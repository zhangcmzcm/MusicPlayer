package org.example.music.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.example.music.common.web.ClientIpResolver;
import org.example.music.user.dto.PasswordResetConfirmationRequest;
import org.example.music.user.service.PasswordResetConfirmationService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name="Users",description="用户管理接口")
public class PasswordResetConfirmationController {
    private final PasswordResetConfirmationService service;
    private final PasswordResetConfirmationRequestReader reader;
    private final ClientIpResolver ips;
    public PasswordResetConfirmationController(PasswordResetConfirmationService service, PasswordResetConfirmationRequestReader reader, ClientIpResolver ips) {
        this.service = service; this.reader = reader; this.ips = ips;
    }
    @PostMapping("/api/v1/users/password-resets")
    @Operation(summary="使用重置凭据设置新密码", description="公开接口，严格UTF-8 JSON最多16384字节。仅resetTicket/newPassword，密码8至72位可打印ASCII且非全空格，保留首尾空格。独立IP固定60秒10次，账户固定900秒10次。消费重置凭据后条件更新密码及认证版本，全部旧登录失效，不自动登录。未知结果不恢复或重试。",
            requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=PasswordResetConfirmationRequest.class))))
    @ApiResponse(responseCode="204",description="已重置，请重新登录",headers=@Header(name="Cache-Control",description="no-store"),content=@Content)
    @ApiResponse(responseCode="400",description="VALIDATION_FAILED / PASSWORD_RESET_VERIFICATION_FAILED / NEW_PASSWORD_SAME_AS_OLD",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="401",description="主动附带无效Bearer时沿用UNAUTHORIZED/INVALID_TOKEN/TOKEN_REVOKED；签名错误或过期可能无ProblemDetail。不带Token可用",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="403",description="FORBIDDEN：沿用安全链，无新增角色限制",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="415",description="UNSUPPORTED_MEDIA_TYPE：需要UTF-8 application/json",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="429",description="PASSWORD_RESET_CONFIRMATION_RATE_LIMITED：仅IP额度",headers=@Header(name="Retry-After",description="IP窗口剩余秒数向上取整，至少1"),content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="503",description="PASSWORD_RESET_CONFIRMATION_UNAVAILABLE；主动附带Token时可能AUTH_STATE_STORE_UNAVAILABLE",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="500",description="INTERNAL_ERROR",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    public ResponseEntity<Void> reset(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        service.reset(reader.read(request),ips.resolve(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}

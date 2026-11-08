package org.example.music.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.example.music.common.web.ClientIpResolver;
import org.example.music.user.dto.PasswordResetVerificationRequest;
import org.example.music.user.dto.PasswordResetVerificationResponse;
import org.example.music.user.service.PasswordResetVerificationService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name="Users",description="用户管理接口")
public class PasswordResetVerificationController {
    private final PasswordResetVerificationService service;
    private final PasswordResetVerificationRequestReader reader;
    private final ClientIpResolver ips;
    public PasswordResetVerificationController(PasswordResetVerificationService service, PasswordResetVerificationRequestReader reader, ClientIpResolver ips) {
        this.service = service; this.reader = reader; this.ips = ips;
    }
    @PostMapping(value="/api/v1/users/password-reset-verifications",produces="application/json")
    @Operation(summary="独立验证找回验证码并签发重置凭据", description="公开接口。严格UTF-8 JSON最多16384字节，仅challengeId/code。与旧组合提交接口共享IP固定60秒10次、账户固定900秒10次额度。正确码原子兑换成该账户最新一张300秒resetTicket，用户表只读。结果未知不自动重试，恢复采用重新申请。",
            requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=PasswordResetVerificationRequest.class))))
    @ApiResponse(responseCode="200",description="验证码已消费并签发重置凭据",headers=@Header(name="Cache-Control",description="no-store"),content=@Content(mediaType="application/json",schema=@Schema(implementation=PasswordResetVerificationResponse.class)))
    @ApiResponse(responseCode="400",description="VALIDATION_FAILED / PASSWORD_RESET_VERIFICATION_FAILED",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="401",description="主动附带无效Bearer时沿用UNAUTHORIZED/INVALID_TOKEN/TOKEN_REVOKED；签名错误或过期可能无ProblemDetail。不带Token可用",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="403",description="FORBIDDEN：沿用安全链，无新增角色限制",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="415",description="UNSUPPORTED_MEDIA_TYPE：需要UTF-8 application/json",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="429",description="PASSWORD_RESET_VERIFICATION_RATE_LIMITED：仅IP额度",headers=@Header(name="Retry-After",description="IP窗口剩余秒数向上取整，至少1"),content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="503",description="PASSWORD_RESET_VERIFICATION_UNAVAILABLE；主动附带Token时可能AUTH_STATE_STORE_UNAVAILABLE",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="500",description="INTERNAL_ERROR",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    public ResponseEntity<PasswordResetVerificationResponse> verify(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.verify(reader.read(request),ips.resolve(request)));
    }
}

package org.example.music.user.controller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.example.music.common.web.ClientIpResolver;
import org.example.music.user.dto.*;
import org.example.music.user.service.PasswordResetRequestService;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @Tag(name="Users",description="用户管理接口")
public class PasswordResetRequestController {
 private final PasswordResetRequestService service;private final PasswordResetRequestReader reader;private final ClientIpResolver ips;
 public PasswordResetRequestController(PasswordResetRequestService service,PasswordResetRequestReader reader,ClientIpResolver ips){this.service=service;this.reader=reader;this.ips=ips;}
 @PostMapping(value="/api/v1/users/password-reset-requests",produces="application/json")
 @Operation(summary="申请找回密码邮箱验证码",description="公开接口。仅email，严格UTF-8 JSON最多16 KiB，规范化后最多120字符。统一202仅表示任务入队，不承诺账户存在或邮件发送；受理后300秒到期，邮箱60秒1次/UTC日10次、IP60秒5次，后台最多一次发送。发送失败不替换旧有效码，明确激活后才替换。",
  requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=PasswordResetRequest.class))))
 @ApiResponse(responseCode="202",description="已受理，不承诺送达",headers=@Header(name="Cache-Control",description="no-store"),content=@Content(mediaType="application/json",schema=@Schema(implementation=PasswordResetRequestResponse.class)))
 @ApiResponse(responseCode="400",description="VALIDATION_FAILED",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
 @ApiResponse(responseCode="401",description="主动附带无效Bearer时沿用UNAUTHORIZED/INVALID_TOKEN/TOKEN_REVOKED；签名错误/过期可能无ProblemDetail；不带Token可用",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
 @ApiResponse(responseCode="403",description="FORBIDDEN：沿用现有安全链，无新增角色限制",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
 @ApiResponse(responseCode="415",description="UNSUPPORTED_MEDIA_TYPE：需要UTF-8 application/json",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
 @ApiResponse(responseCode="429",description="PASSWORD_RESET_REQUEST_RATE_LIMITED",headers=@Header(name="Retry-After",description="全部阻塞额度最大等待秒数，向上取整至少1"),content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
 @ApiResponse(responseCode="503",description="PASSWORD_RESET_REQUEST_UNAVAILABLE；附带Token认证查询失败沿用AUTH_STATE_STORE_UNAVAILABLE",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
 @ApiResponse(responseCode="500",description="INTERNAL_ERROR",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
 public ResponseEntity<PasswordResetRequestResponse> request(HttpServletRequest request){return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.request(reader.read(request),ips.resolve(request),MDC.get("traceId")));}
}

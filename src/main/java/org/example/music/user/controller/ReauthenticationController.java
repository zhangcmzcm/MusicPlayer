package org.example.music.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.example.music.security.AuthenticatedSessionContext;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.dto.ReauthenticationRequest;
import org.example.music.user.dto.ReauthenticationResponse;
import org.example.music.user.exception.InvalidTokenException;
import org.example.music.user.service.ReauthenticationService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Users", description = "用户管理接口")
public class ReauthenticationController {
    private final ReauthenticationRequestReader reader;
    private final ReauthenticationService service;

    public ReauthenticationController(ReauthenticationRequestReader reader, ReauthenticationService service) {
        this.reader = reader;
        this.service = service;
    }

    @PostMapping("/api/v1/users/me/reauthentications")
    @Operation(summary = "再次验证本人身份", description = "普通有效JWT即可，兼容缺auth_time的旧Token。PASSWORD/password或EMAIL_CODE/challengeId/code严格二选一，身份仅取可信主体。每人首次准入900秒内最多10次，两方式与全部Token共享，失败不退款；邮箱同一ACTIVE第五次错误删除、成功原子消费。成功签发新auth_time的Token，保持原登录角色和验证版本，旧Token及其他登录不撤销、不修改账户。auth_time取真正匹配完成时间，后续耗时不刷新；已消费后失败不恢复，确认或响应丢失可能已消费/签出，最终DB复核不是跨存储原子快照。",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReauthenticationRequest.class))))
    @ApiResponse(responseCode = "200", description = "再次验证成功，四字段，不返回用户资料", headers = @Header(name = "Cache-Control", description = "no-store"), content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReauthenticationResponse.class)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED，包括缺体、重复键/未知字段、混用、强制类型、非JSON或非UTF-8媒体类型", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED、INVALID_TOKEN、TOKEN_REVOKED、REAUTHENTICATION_FAILED；业务验证失败不等于原登录失效。JWT签名错误/过期沿用资源服务器现有响应，可能无ProblemDetail", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "CURRENT_EMAIL_NOT_BOUND，仅EMAIL_CODE首轮发现本人未绑定邮箱，可选PASSWORD", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "REAUTHENTICATION_RATE_LIMITED，不消费验证码或延长窗口", headers = @Header(name = "Retry-After", description = "剩余等待秒数向上取整，1—900"), content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "REAUTHENTICATION_UNAVAILABLE或认证链AUTH_STATE_STORE_UNAVAILABLE；可能已计额、计错或消费，不自动重试/恢复", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR，非预期验证或签发故障", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ReauthenticationResponse> reauthenticate(HttpServletRequest request,
            @Parameter(hidden = true) Authentication authentication) {
        var context = AuthenticatedSessionContext.from(authentication).orElseThrow(InvalidTokenException::new);
        var actor = (AuthenticatedUserPrincipal) authentication.getPrincipal();
        var input = reader.read(request);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .body(service.reauthenticate(actor, context, input));
    }
}

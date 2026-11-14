package org.example.music.playback.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;
import org.example.music.playback.dto.SavePlaybackProgressRequest;
import org.example.music.playback.dto.SavePlaybackProgressResponse;
import org.example.music.playback.service.PlaybackProgressService;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/playback-progress")
@Tag(name = "播放进度", description = "当前用户按设备保存最近播放状态")
public class PlaybackProgressController {
    private static final String DEVICE_UUID =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private final PlaybackProgressRequestReader reader;
    private final PlaybackProgressService service;

    public PlaybackProgressController(PlaybackProgressRequestReader reader, PlaybackProgressService service) {
        this.reader = reader;
        this.service = service;
    }

    @PutMapping("/{deviceId}")
    @Operation(summary = "保存本设备最近播放进度",
            description = "按当前用户与设备保存一份状态，位置为整数秒。USER仅可保存已发布歌曲，ADMIN按登录角色判断。"
                    + "客户端持久递增sequence，大号更新，同号同内容保留原时间，旧号或同号异内容返回409及currentSequence。"
                    + "每账号全部设备共享首次请求起60秒默认120次额度。",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = SavePlaybackProgressRequest.class))))
    @ApiResponse(responseCode = "200", description = "新状态已保存或当前相同事件重试成功",
            headers = @Header(name = "Cache-Control", description = "no-store"),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SavePlaybackProgressResponse.class)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED，UUID、严格整数JSON、字段范围或有效时长上限不合法",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "沿用认证体系的UNAUTHORIZED、INVALID_TOKEN、TOKEN_REVOKED及JWT解码失败响应",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN，方法授权失败",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TRACK_NOT_FOUND，歌曲不存在或当前角色不可见",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "PLAYBACK_PROGRESS_STALE_SEQUENCE或PLAYBACK_PROGRESS_SEQUENCE_CONFLICT，包含currentSequence",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE，需要application/json",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "PLAYBACK_PROGRESS_RATE_LIMITED，账号保存额度耗尽",
            headers = @Header(name = "Retry-After", description = "窗口剩余秒数向上取整，至少1"),
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "PLAYBACK_PROGRESS_RATE_LIMIT_UNAVAILABLE、PLAYBACK_PROGRESS_STORE_UNAVAILABLE或AUTH_STATE_STORE_UNAVAILABLE",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<SavePlaybackProgressResponse> save(
            @Parameter(description = "持久化设备UUID，接收后规范化为小写",
                    schema = @Schema(type = "string", format = "uuid", pattern = DEVICE_UUID))
            @PathVariable("deviceId") @Pattern(regexp = DEVICE_UUID) String deviceId,
            HttpServletRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
        var input = reader.read(request);
        var result = service.save(deviceId.toLowerCase(Locale.ROOT), input, actor);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}

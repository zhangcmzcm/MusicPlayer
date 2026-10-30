package org.example.music.track.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.util.List;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.service.TrackCoverConditions;
import org.example.music.track.service.TrackCoverService;
import org.example.music.track.web.TrackCoverResponseWriter;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tracks")
@Tag(name = "歌曲封面", description = "鉴权后读取私有封面图片")
public class TrackCoverController {
    private final TrackCoverService service;
    private final TrackCoverResponseWriter writer;

    public TrackCoverController(TrackCoverService service, TrackCoverResponseWriter writer) {
        this.service = service;
        this.writer = writer;
    }

    @GetMapping("/{trackId}/cover")
    @Operation(summary = "获取歌曲封面", description = "管理员可读取全部状态，其他用户含上传者本人仅可读取已发布歌曲封面。支持 If-None-Match，忽略 Range。")
    @ApiResponse(responseCode = "200", description = "完整图片", headers = {
            @Header(name = "ETag", description = "一致性校验后的封面摘要标签"),
            @Header(name = "Cache-Control", description = "private, no-cache"),
            @Header(name = "Vary", description = "包含 Authorization"),
            @Header(name = "Content-Length", description = "图片字节数")}, content = {
            @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
            @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")),
            @Content(mediaType = "image/webp", schema = @Schema(type = "string", format = "binary"))})
    @ApiResponse(responseCode = "304", description = "已验证权限和对象状态，标签匹配，无响应体", headers = {
            @Header(name = "ETag", description = "当前封面标签"),
            @Header(name = "Cache-Control", description = "private, no-cache"),
            @Header(name = "Vary", description = "包含 Authorization")}, content = @Content)
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "需要有效认证，JWT 解码失败沿用资源服务器响应", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TRACK_NOT_FOUND 或 COVER_NOT_FOUND", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "COVER_STORAGE_INCONSISTENT", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "MEDIA_STORAGE_UNAVAILABLE、TRACK_STORE_UNAVAILABLE 或 AUTH_STATE_STORE_UNAVAILABLE", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void getCover(
            @Parameter(description = "歌曲正整数 ID") @PathVariable("trackId") @Positive Long trackId,
            @Parameter(description = "封面缓存标签，支持列表、弱标签和 *")
            @RequestHeader(name = "If-None-Match", required = false) List<String> ifNoneMatch,
            @AuthenticationPrincipal AuthenticatedUserPrincipal actor,
            HttpServletResponse response
    ) throws IOException {
        var cover = service.prepare(trackId, actor);
        String condition = ifNoneMatch == null ? null : String.join(",", ifNoneMatch);
        if (TrackCoverConditions.matches(condition, cover.etag())) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            TrackCoverResponseWriter.cacheHeaders(cover, response);
            return;
        }
        writer.write(cover, response);
    }

    @ExceptionHandler(IOException.class)
    public void interrupted(IOException exception) throws IOException {
        // Rethrowing from the local handler lets the container abort a committed response.
        throw exception;
    }
}

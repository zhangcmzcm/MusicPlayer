package org.example.music.track.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.time.Instant;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.service.TrackStreamConditions;
import org.example.music.track.service.TrackStreamRangeParser;
import org.example.music.track.service.TrackStreamService;
import org.example.music.track.web.TrackStreamResponseWriter;
import org.springframework.http.HttpHeaders;
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
@Tag(name="歌曲播放",description="带 Bearer 请求头的原始音频流，不转码")
public class TrackStreamController {
    private final TrackStreamService service;
    private final TrackStreamResponseWriter writer;
    public TrackStreamController(TrackStreamService service,TrackStreamResponseWriter writer) { this.service=service;this.writer=writer; }

    @GetMapping("/{trackId}/stream")
    @Operation(summary="播放歌曲",description="管理员可试听全部状态，其他用户仅已发布。支持单区间 Range 和条件请求；多区间/未知单位忽略，非法单区间返回 416。原生 audio.src 媒体授权不在本接口范围。",parameters={
            @Parameter(name="Range",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,description="bytes=start-end / start- / -suffix，单区间"),
            @Parameter(name="If-Range",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,description="强标签或可靠日期匹配才应用 Range"),
            @Parameter(name="If-Match",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,description="强比较，不满足 412"),
            @Parameter(name="If-Unmodified-Since",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,description="HTTP-date，仅无 If-Match 时生效"),
            @Parameter(name="If-None-Match",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,description="弱比较，匹配 304"),
            @Parameter(name="If-Modified-Since",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,description="HTTP-date，仅无 If-None-Match 时生效")})
    @ApiResponse(responseCode="200",description="完整音频；Content-Length、Accept-Ranges: bytes、ETag、Last-Modified、private, no-cache、Vary: Authorization",headers={@Header(name="Content-Length",description="本次音频响应字节数"),@Header(name="Accept-Ranges",description="bytes"),@Header(name="ETag",description="已验证 SHA-256 强标签"),@Header(name="Last-Modified",description="音频资产 UTC HTTP-date"),@Header(name="Cache-Control",description="private, no-cache"),@Header(name="Vary",description="包含 Authorization")},content={@Content(mediaType="audio/mpeg",schema=@Schema(type="string",format="binary")),@Content(mediaType="audio/flac",schema=@Schema(type="string",format="binary"))})
    @ApiResponse(responseCode="206",description="精确单区间音频与区间长度；缓存头同 200",headers={@Header(name="Content-Range",description="bytes start-end/total"),@Header(name="Content-Length",description="本次音频响应字节数"),@Header(name="Accept-Ranges",description="bytes"),@Header(name="ETag",description="已验证 SHA-256 强标签"),@Header(name="Last-Modified",description="音频资产 UTC HTTP-date"),@Header(name="Cache-Control",description="private, no-cache"),@Header(name="Vary",description="包含 Authorization")},content={@Content(mediaType="audio/mpeg",schema=@Schema(type="string",format="binary")),@Content(mediaType="audio/flac",schema=@Schema(type="string",format="binary"))})
    @ApiResponse(responseCode="304",description="无响应体，不读取音频流，保留缓存验证头",headers={@Header(name="ETag",description="当前强标签"),@Header(name="Last-Modified",description="HTTP-date"),@Header(name="Cache-Control",description="private, no-cache"),@Header(name="Vary",description="包含 Authorization")},content=@Content)
    @ApiResponse(responseCode="400",description="VALIDATION_FAILED",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="401",description="需要有效认证；JWT 解码失败沿用资源服务器响应",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="404",description="TRACK_NOT_FOUND：不存在、不可见或对象丢失",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="412",description="PRECONDITION_FAILED",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="416",description="RANGE_NOT_SATISFIABLE",headers=@Header(name="Content-Range",description="bytes */total"),content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="500",description="INTERNAL_ERROR",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="502",description="AUDIO_STORAGE_INCONSISTENT",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="503",description="MEDIA_STORAGE_UNAVAILABLE、TRACK_STORE_UNAVAILABLE、AUTH_STATE_STORE_UNAVAILABLE",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    public void stream(@Parameter(description="歌曲正整数 ID") @PathVariable("trackId") @Positive Long trackId,
            @Parameter(hidden=true) @RequestHeader HttpHeaders headers,@AuthenticationPrincipal AuthenticatedUserPrincipal actor,
            HttpServletRequest request,HttpServletResponse response) throws IOException {
        var prepared=service.prepare(trackId,actor,Instant.now());
        if(TrackStreamConditions.evaluate(headers,prepared.etag(),prepared.lastModified())==304) {
            response.setStatus(304); TrackStreamResponseWriter.cacheHeaders(prepared,response); return;
        }
        if(request.getMethod().equals("HEAD")) { TrackStreamResponseWriter.contentHeaders(prepared,null,response); return; }
        var range=TrackStreamConditions.allowsRange(headers.get(HttpHeaders.IF_RANGE),prepared.etag(),prepared.lastModified(),prepared.dateStrong())
                ? TrackStreamRangeParser.parse(headers.get(HttpHeaders.RANGE),prepared.snapshot().metadata().sizeBytes()) : null;
        writer.write(prepared,range,response);
    }

    @ExceptionHandler(IOException.class)
    public void interrupted(IOException exception) throws IOException { throw exception; }
}

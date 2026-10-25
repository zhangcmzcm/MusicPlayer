package org.example.music.track.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.example.music.track.dto.TrackImportResponse;
import org.example.music.track.dto.TrackImportResponseMapper;
import org.example.music.track.service.MediaImportService;
import org.example.music.track.service.TrackImportRateLimiter;
import org.example.music.track.service.TrackImportResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/track-imports")
@Tag(name = "歌曲导入", description = "管理员音频上传与导入接口")
public class TrackImportController {

    private final MediaImportService mediaImportService;
    private final TrackImportRateLimiter trackImportRateLimiter;

    public TrackImportController(
            MediaImportService mediaImportService,
            TrackImportRateLimiter trackImportRateLimiter
    ) {
        this.mediaImportService = mediaImportService;
        this.trackImportRateLimiter = trackImportRateLimiter;
    }

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "导入音频文件",
            description = "上传一个 MP3 或 FLAC 文件，提取歌曲元数据和内嵌封面，并创建 DRAFT 状态的歌曲草稿"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "歌曲导入成功", content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TrackImportResponse.class))),
            @ApiResponse(responseCode = "200", description = "幂等重试，返回已有导入结果", content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TrackImportResponse.class))),
            @ApiResponse(responseCode = "400", description = "multipart 请求参数无效", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "需要登录", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "需要管理员角色", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "导入幂等键冲突或导入正在处理", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "413", description = "音频文件超过大小限制", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "415", description = "音频格式不受支持", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "音频元数据无效", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "导入请求超过频率限制", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "媒体存储或导入状态存储不可用", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TrackImportResponse> importTrack(
            @Parameter(description = "客户端生成的幂等键")
            @RequestParam("uploadId")
            UUID uploadId,

            @Parameter(description = "MP3 或 FLAC 音频文件")
            @RequestPart("file")
            MultipartFile file,

            @AuthenticationPrincipal
            Jwt jwt
    ) {
        int userId = Integer.parseInt(jwt.getSubject());
        trackImportRateLimiter.check(userId);
        TrackImportResult result = mediaImportService.importTrack(uploadId, file, userId);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(TrackImportResponseMapper.from(result));
    }
}

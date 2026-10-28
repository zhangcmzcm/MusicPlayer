package org.example.music.track.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.dto.TrackPublishResponse;
import org.example.music.track.service.TrackPublishService;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tracks")
@Tag(name = "歌曲发布", description = "管理员歌曲发布接口")
public class TrackPublishController {

    private final TrackPublishService trackPublishService;

    public TrackPublishController(TrackPublishService trackPublishService) {
        this.trackPublishService = trackPublishService;
    }

    @PostMapping("/{trackId}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "发布歌曲",
            description = "将 DRAFT 状态歌曲发布为 PUBLISHED；重复发布或并发修改返回稳定错误码"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "歌曲发布成功", content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TrackPublishResponse.class))),
            @ApiResponse(responseCode = "400", description = "trackId 格式无效", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "需要登录", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "需要管理员角色", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "歌曲不存在", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "歌曲不是草稿或发生版本冲突", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "歌曲存储不可用", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TrackPublishResponse> publish(
            @Parameter(description = "歌曲 ID")
            @PathVariable("trackId")
            @Positive
            Long trackId,

            @AuthenticationPrincipal
            AuthenticatedUserPrincipal actor
    ) {
        return ResponseEntity.ok(trackPublishService.publish(trackId, actor.userId()));
    }
}

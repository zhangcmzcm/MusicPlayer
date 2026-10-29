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
import org.example.music.track.dto.TrackArchiveResponse;
import org.example.music.track.service.TrackArchiveService;
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
@Tag(name = "歌曲归档", description = "管理员歌曲归档接口")
public class TrackArchiveController {

    private final TrackArchiveService trackArchiveService;

    public TrackArchiveController(TrackArchiveService trackArchiveService) {
        this.trackArchiveService = trackArchiveService;
    }

    @PostMapping("/{trackId}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "归档歌曲",
            description = "将 DRAFT 或 PUBLISHED 歌曲设置为 ARCHIVED；归档后保留数据库记录和对象存储数据"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "歌曲归档成功", content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TrackArchiveResponse.class))),
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
            @ApiResponse(responseCode = "409", description = "歌曲已经归档或发生版本冲突", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "歌曲存储不可用", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TrackArchiveResponse> archive(
            @Parameter(description = "歌曲 ID")
            @PathVariable("trackId")
            @Positive
            Long trackId,

            @AuthenticationPrincipal
            AuthenticatedUserPrincipal actor
    ) {
        return ResponseEntity.ok(trackArchiveService.archive(trackId, actor.userId()));
    }
}
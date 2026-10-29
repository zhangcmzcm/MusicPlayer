package org.example.music.track.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.example.music.track.service.TrackDeleteService;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tracks")
@Tag(name = "歌曲删除", description = "管理员删除归档歌曲接口")
public class TrackDeleteController {

    private final TrackDeleteService trackDeleteService;

    public TrackDeleteController(TrackDeleteService trackDeleteService) {
        this.trackDeleteService = trackDeleteService;
    }

    @DeleteMapping("/{trackId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "删除归档歌曲",
            description = "物理删除 ARCHIVED 状态歌曲及其媒体资产和 MinIO/S3 对象；保留导入历史"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "歌曲删除成功"),
            @ApiResponse(responseCode = "400", description = "trackId 格式无效", content = @Content(
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "需要登录", content = @Content(
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "需要管理员角色", content = @Content(
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "歌曲不存在或已删除", content = @Content(
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "歌曲不是 ARCHIVED 状态", content = @Content(
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "媒体清理或数据库删除失败", content = @Content(
                    mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "歌曲 ID")
            @PathVariable("trackId")
            @Positive
            Long trackId
    ) {
        trackDeleteService.delete(trackId);
        return ResponseEntity.noContent().build();
    }
}
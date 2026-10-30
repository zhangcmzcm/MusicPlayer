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
import org.example.music.track.dto.TrackDetailResponse;
import org.example.music.track.service.TrackDetailService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tracks")
@Tag(name = "歌曲详情", description = "已登录用户歌曲详情接口")
public class TrackDetailController {

    private final TrackDetailService trackDetailService;

    public TrackDetailController(TrackDetailService trackDetailService) {
        this.trackDetailService = trackDetailService;
    }

    @GetMapping("/{trackId}")
    @Operation(
            summary = "获取歌曲详情",
            description = "管理员可查看所有状态；其他已登录用户（含上传者本人）只能查看 PUBLISHED。封面读取接口后续实现。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "歌曲详情读取成功", content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TrackDetailResponse.class))),
            @ApiResponse(responseCode = "400", description = "trackId 格式无效", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "需要登录", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "TRACK_NOT_FOUND：不存在或不可见", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR：内部一致性故障", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "TRACK_STORE_UNAVAILABLE 或 AUTH_STATE_STORE_UNAVAILABLE", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TrackDetailResponse> getDetail(
            @Parameter(description = "歌曲 ID")
            @PathVariable("trackId")
            @Positive
            Long trackId,

            @AuthenticationPrincipal
            AuthenticatedUserPrincipal actor
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(trackDetailService.getDetail(trackId, actor));
    }
}

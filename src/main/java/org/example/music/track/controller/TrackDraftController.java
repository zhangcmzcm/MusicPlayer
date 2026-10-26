package org.example.music.track.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.music.track.dto.TrackDraftRequest;
import org.example.music.track.dto.TrackDraftResponse;
import org.example.music.track.service.TrackDraftService;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tracks")
@Tag(name = "歌曲草稿编辑", description = "管理员歌曲草稿字段编辑接口")
public class TrackDraftController {

    private final TrackDraftService trackDraftService;

    public TrackDraftController(TrackDraftService trackDraftService) {
        this.trackDraftService = trackDraftService;
    }

    @PatchMapping(
            value = "/{trackId}/draft",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "编辑歌曲草稿",
            description = "修改 DRAFT 状态歌曲的标题、艺术家、专辑、曲序和碟片序号；修改后歌曲仍保持 DRAFT 状态"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "歌曲草稿修改成功", content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TrackDraftResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求字段或版本号无效", content = @Content(
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
            @ApiResponse(responseCode = "409", description = "歌曲不是草稿或版本号冲突", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "歌曲存储不可用", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TrackDraftResponse> updateDraft(
            @Parameter(description = "歌曲 ID")
            @PathVariable("trackId")
            Long trackId,

            @Valid @RequestBody
            TrackDraftRequest request,

            @AuthenticationPrincipal
            Jwt jwt
    ) {
        int userId = Integer.parseInt(jwt.getSubject());
        return ResponseEntity.ok(trackDraftService.updateDraft(trackId, request, userId));
    }
}

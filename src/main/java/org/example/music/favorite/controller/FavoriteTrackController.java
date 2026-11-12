package org.example.music.favorite.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.example.music.favorite.service.FavoriteTrackService;
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
@RequestMapping("/api/v1/users/me/favorites")
@Tag(name = "歌曲收藏", description = "当前登录用户的歌曲收藏")
public class FavoriteTrackController {
    private final FavoriteTrackService service;

    public FavoriteTrackController(FavoriteTrackService service) {
        this.service = service;
    }

    @PutMapping("/{trackId}")
    @Operation(summary = "收藏歌曲", description = "仅为当前用户收藏。USER仅可收藏PUBLISHED，ADMIN可收藏全部现存状态。"
            + "每次重新检查可见性，首次与重复均返回204，保留首次收藏时间。无请求体，附带内容和未知查询参数不参与业务。")
    @ApiResponse(responseCode = "204", description = "已收藏，空响应体", headers = @Header(name = "Cache-Control", description = "no-store"), content = @Content)
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED，歌曲ID非法", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "需要有效认证，JWT解码失败沿用资源服务器响应", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TRACK_NOT_FOUND，歌曲不存在或不可见", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "FAVORITE_STORE_UNAVAILABLE或AUTH_STATE_STORE_UNAVAILABLE", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> favorite(
            @Parameter(description = "歌曲正整数ID", schema = @Schema(type = "integer", format = "int64", minimum = "1", maximum = "9223372036854775807"))
            @PathVariable("trackId") @Positive Long trackId,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor
    ) {
        service.favorite(trackId, actor);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}

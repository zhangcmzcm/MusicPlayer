package org.example.music.favorite.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.music.common.dto.PageResponse;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.favorite.dto.FavoriteListItemResponse;
import org.example.music.favorite.service.FavoriteListParameters;
import org.example.music.favorite.service.FavoriteListService;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/favorites")
@Tag(name = "本人收藏列表", description = "分页查询本人可见的收藏歌曲")
public class FavoriteListController {
    private final FavoriteListService service;

    public FavoriteListController(FavoriteListService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "分页本人收藏列表", description = "仅本人收藏；管理员默认全部状态，其他用户含上传者本人仅已发布；单排序及同向 ID 次排序，不访问对象存储。未知、空值或重复参数返回 400。", parameters = {
            @Parameter(name = "page", description = "从 0 开始的页码；page×size 不超过 10000", schema = @Schema(type = "integer", minimum = "0", defaultValue = "0")),
            @Parameter(name = "size", description = "页大小", schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20")),
            @Parameter(name = "sort", description = "单一 字段,方向；字段仅 id/title/artistName/createdAt/favoritedAt，方向仅 asc/desc", schema = @Schema(type = "string", defaultValue = "favoritedAt,desc")),
            @Parameter(name = "status", description = "可选状态，非管理员筛选隐藏状态返回 403", schema = @Schema(type = "string", allowableValues = {"DRAFT", "PUBLISHED", "ARCHIVED"}))})
    @ApiResponse(responseCode = "200", description = "分页列表，可为空；Cache-Control: no-store")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "需要有效登录，沿用现有认证错误", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN：非管理员请求隐藏状态", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR：本页封面资产非法", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "FAVORITE_STORE_UNAVAILABLE 或 AUTH_STATE_STORE_UNAVAILABLE", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PageResponse<FavoriteListItemResponse>> list(
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> raw,
            @AuthenticationPrincipal AuthenticatedUserPrincipal actor
    ) {
        var parameters = FavoriteListParameters.from(raw);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(parameters, actor));
    }
}

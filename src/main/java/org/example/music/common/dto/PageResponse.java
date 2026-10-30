package org.example.music.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "分页结果，计数仅包含当前可见筛选范围")
public record PageResponse<T>(
        @Schema(description = "本页数据") List<T> items,
        @Schema(description = "从 0 开始的页码") int page,
        @Schema(description = "页大小") int size,
        @Schema(description = "匹配总数") long totalElements,
        @Schema(description = "总页数，空结果为 0") long totalPages,
        @Schema(description = "是否还有下一页") boolean hasNext
) {
    public PageResponse {
        items = List.copyOf(items);
    }
}

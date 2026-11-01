package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = UpdateCurrentUserRequestDeserializer.class)
@Schema(description = "修改本人用户名；仅允许 username 一个字段，拒绝重复键和未知字段",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateCurrentUserRequest(
        @NotBlank
        @Size(min = 3, max = 20)
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "must contain only letters, digits, and underscores")
        @Schema(description = "保留大小写，不删除首尾空白；仅允许英文字母、数字和下划线", example = "alice_new", minLength = 3, maxLength = 20)
        String username
) {
}

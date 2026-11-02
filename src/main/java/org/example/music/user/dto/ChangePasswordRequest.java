package org.example.music.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = ChangePasswordRequestDeserializer.class)
@Schema(description = "修改本人密码，严格接受两个字符串字段", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChangePasswordRequest(
        @NotBlank @Size(min = 1, max = 128)
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(description = "原密码，按原值验证，兼容历史字符范围", format = "password",
                accessMode = Schema.AccessMode.WRITE_ONLY, minLength = 1, maxLength = 128)
        String oldPassword,
        @NotBlank @Size(min = 8, max = 72)
        @Pattern(regexp = "[\\x20-\\x7E]+", message = "must contain only printable ASCII characters (U+0020 to U+007E)")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(description = "新密码，非全空格的可打印 ASCII，首尾空格保留", format = "password",
                accessMode = Schema.AccessMode.WRITE_ONLY, minLength = 8, maxLength = 72,
                pattern = "^[\\x20-\\x7E]+(?![\\s\\S])")
        String newPassword
) {
    @Override
    public String toString() {
        return "ChangePasswordRequest[oldPassword=<redacted>, newPassword=<redacted>]";
    }
}

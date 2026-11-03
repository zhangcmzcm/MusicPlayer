package org.example.music.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Base64;
import org.example.music.user.domain.ReauthenticationMethod;

@Schema(description = "本人再次验证，按method严格二选一", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReauthenticationRequest(
        @NotNull @Schema(description = "PASSWORD或EMAIL_CODE") ReauthenticationMethod method,
        @Size(min = 1, max = 128) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(description = "PASSWORD必填，原值验证，非全空白1—128字符，兼容历史密码", format = "password", minLength = 1, maxLength = 128, accessMode = Schema.AccessMode.WRITE_ONLY)
        String password,
        @Pattern(regexp = "[A-Za-z0-9_-]{43}") @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(description = "EMAIL_CODE必填，规范Base64URL无填充32字节请求标识", minLength = 43, maxLength = 43, accessMode = Schema.AccessMode.WRITE_ONLY)
        String challengeId,
        @Pattern(regexp = "[0-9]{6}") @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(description = "EMAIL_CODE必填，六位ASCII数字", minLength = 6, maxLength = 6, accessMode = Schema.AccessMode.WRITE_ONLY)
        String code
) {
    @JsonIgnore @AssertTrue(message = "Fields must match the selected reauthentication method")
    public boolean isFieldsValid() {
        if (method == ReauthenticationMethod.PASSWORD) return password != null && !password.isBlank() && challengeId == null && code == null;
        if (method != ReauthenticationMethod.EMAIL_CODE || password != null || code == null || challengeId == null) return false;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(challengeId);
            return bytes.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(challengeId);
        } catch (IllegalArgumentException failure) { return false; }
    }

    @Override public String toString() { return "ReauthenticationRequest[method=" + method + ", credentials=<redacted>]"; }
}

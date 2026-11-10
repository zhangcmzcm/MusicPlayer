package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

public record PasswordResetConfirmationRequest(
        @NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}")
        @Schema(accessMode=Schema.AccessMode.WRITE_ONLY, description="规范Base64URL重置凭据，32字节无填充", minLength=43, maxLength=43) String resetTicket,
        @NotNull @Size(min=8,max=72) @Pattern(regexp="(?s)(?=.*[^ ])[\\x20-\\x7E]+")
        @Schema(accessMode=Schema.AccessMode.WRITE_ONLY, description="8至72位可打印ASCII，非全空格，保留首尾空格", minLength=8,maxLength=72) String newPassword) {
    @Override public String toString() { return "PasswordResetConfirmationRequest[redacted]"; }
}

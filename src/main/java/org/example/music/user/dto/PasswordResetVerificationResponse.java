package org.example.music.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record PasswordResetVerificationResponse(
        @Schema(description="仅用于第三步请求体的一次性重置凭据",minLength=43,maxLength=43) String resetTicket,
        @Schema(description="Redis签发起300秒到期，UTC ISO-8601") Instant expiresAt) {
    @Override public String toString() { return "PasswordResetVerificationResponse[redacted]"; }
}

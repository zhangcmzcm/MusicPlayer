package org.example.music.user.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
@Schema(description="找回密码申请受理结果，不承诺账户存在或邮件已发送")
public record PasswordResetRequestResponse(
 @Schema(description="32字节随机标识的规范Base64URL编码", minLength=43,maxLength=43) String challengeId,
 @Schema(description="原申请到期时间，UTC") Instant expiresAt) {
 @Override public String toString(){return "PasswordResetRequestResponse[redacted]";}
}

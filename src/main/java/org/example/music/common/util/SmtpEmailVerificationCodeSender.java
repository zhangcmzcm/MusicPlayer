package org.example.music.common.util;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.example.music.email.exception.EmailSendFailedException;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class SmtpEmailVerificationCodeSender implements EmailVerificationCodeSender {

    private static final DateTimeFormatter EXPIRES_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private final JavaMailSender javaMailSender;
    private final String fromAddress;

    public SmtpEmailVerificationCodeSender(
            JavaMailSender javaMailSender,
            @Value("${app.mail.from}") String fromAddress
    ) {
        this.javaMailSender = javaMailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(
            String email,
            VerificationCodePurpose purpose,
            String code,
            Instant expiresAt
    ) {
        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message,
                    false,
                    StandardCharsets.UTF_8.name()
            );
            helper.setFrom(fromAddress);
            helper.setTo(email);
            helper.setSubject("音乐播放器邮箱验证码");
            helper.setText("""
                    您的邮箱验证码是：%s

                    验证码将在 %s 失效。若非本人操作，请忽略此邮件。
                    """.formatted(code, EXPIRES_AT_FORMAT.format(expiresAt)), false);
            javaMailSender.send(message);
        } catch (MessagingException | MailException exception) {
            throw new EmailSendFailedException("Email verification code delivery failed", exception);
        }
    }
}
package org.example.music.user.email;

import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;
import org.example.music.user.exception.PasswordResetSendFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class SmtpPasswordResetEmailSender implements PasswordResetEmailSender {
    private final JavaMailSender client;
    private final String fromAddress;

    @Autowired
    public SmtpPasswordResetEmailSender(IsolatedPasswordResetMailClientFactory factory,
            @Value("${app.mail.from}") String fromAddress, org.example.music.user.config.PasswordResetProperties properties) {
        this(factory.createClient(properties.getSendTimeout()), fromAddress);
    }

    SmtpPasswordResetEmailSender(JavaMailSender client, String fromAddress) {
        this.client = client;
        this.fromAddress = fromAddress;
    }

    @Override public void send(PasswordResetMessage envelope) {
        try {
            var message = client.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(envelope.email());
            helper.setSubject("音乐播放器找回密码验证码");
            helper.setText("""
                    您的找回密码验证码是：%s

                    用于找回账户密码，验证码发送不会直接修改密码。请以最新申请为准。
                    验证码将在 %s 失效。若非本人操作，请忽略此邮件。
                    """.formatted(envelope.code(), envelope.expiresAt()), false);
            client.send(message);
        } catch (MessagingException | MailException failure) {
            throw new PasswordResetSendFailedException(failure);
        }
    }
}

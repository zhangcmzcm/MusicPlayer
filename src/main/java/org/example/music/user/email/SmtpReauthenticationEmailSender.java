package org.example.music.user.email;

import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;
import org.example.music.user.exception.ReauthenticationEmailSendFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class SmtpReauthenticationEmailSender implements ReauthenticationEmailSender {
    private final JavaMailSender client;
    private final String fromAddress;

    @Autowired
    public SmtpReauthenticationEmailSender(IsolatedReauthenticationMailClientFactory factory,
            @Value("${app.mail.from}") String fromAddress) {
        this(factory.createClient(), fromAddress);
    }

    SmtpReauthenticationEmailSender(JavaMailSender client, String fromAddress) {
        this.client = client;
        this.fromAddress = fromAddress;
    }

    @Override public void send(ReauthenticationEmailMessage envelope) {
        try {
            var message = client.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(envelope.recipient());
            helper.setSubject("音乐播放器身份验证验证码");
            helper.setText("""
                    您的身份验证验证码是：%s

                    用于修改邮箱等敏感操作前的身份验证，不会直接修改邮箱。
                    验证码将在 %s 失效。若非本人操作，请忽略此邮件。
                    """.formatted(envelope.code(), envelope.expiresAt()), false);
            client.send(message);
        } catch (MessagingException | MailException failure) {
            throw new ReauthenticationEmailSendFailedException(failure);
        }
    }
}

package org.example.music.user.email;

import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;
import org.example.music.user.exception.EmailChangeSendFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class SmtpEmailChangeEmailSender implements EmailChangeEmailSender {
    private final JavaMailSender client;
    private final String fromAddress;

    @Autowired
    public SmtpEmailChangeEmailSender(IsolatedEmailChangeMailClientFactory factory,
            @Value("${app.mail.from}") String fromAddress) {
        this(factory.createClient(), fromAddress);
    }

    SmtpEmailChangeEmailSender(JavaMailSender client, String fromAddress) {
        this.client = client;
        this.fromAddress = fromAddress;
    }

    @Override public void send(EmailChangeMessage envelope) {
        try {
            var message = client.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(envelope.recipient());
            helper.setSubject("音乐播放器新邮箱变更验证码");
            helper.setText("""
                    您的新邮箱变更验证码是：%s

                    用于确认此邮箱的归属，验证码发送不会直接修改账户邮箱；完成确认后才更新绑定。
                    验证码将在 %s 失效。若非本人操作，请忽略此邮件。
                    """.formatted(envelope.code(), envelope.expiresAt()), false);
            client.send(message);
        } catch (MessagingException | MailException failure) {
            throw new EmailChangeSendFailedException(failure);
        }
    }
}

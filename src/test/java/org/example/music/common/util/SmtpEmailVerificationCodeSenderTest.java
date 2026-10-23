package org.example.music.common.util;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.Properties;
import org.example.music.email.exception.EmailSendFailedException;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpEmailVerificationCodeSenderTest {

    private final JavaMailSender javaMailSender = mock(JavaMailSender.class);
    private final SmtpEmailVerificationCodeSender sender =
            new SmtpEmailVerificationCodeSender(javaMailSender, "music@example.com");

    @Test
    void should_build_message_with_code_and_expiry() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(javaMailSender.createMimeMessage()).thenReturn(message);
        Instant expiresAt = Instant.parse("2026-10-22T12:40:00Z");

        sender.send(
                "alice@example.com",
                VerificationCodePurpose.REGISTER,
                "123456",
                expiresAt
        );

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender).send(captor.capture());
        assertTrue(captor.getValue().getSubject() != null && !captor.getValue().getSubject().isBlank());
        String content = String.valueOf(captor.getValue().getContent());
        assertTrue(content.contains("123456"));
        assertTrue(content.contains("2026-10-22 12:40:00 UTC"));
    }

    @Test
    void should_translate_smtp_failure() {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(javaMailSender.createMimeMessage()).thenReturn(message);
        doThrow(new MailSendException("smtp failed"))
                .when(javaMailSender)
                .send(any(MimeMessage.class));

        assertThrows(
                EmailSendFailedException.class,
                () -> sender.send(
                        "alice@example.com",
                        VerificationCodePurpose.REGISTER,
                        "123456",
                        Instant.parse("2026-10-22T12:40:00Z")
                )
        );
    }
}
package org.example.music.user.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.Properties;
import org.example.music.user.exception.ReauthenticationEmailSendFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpReauthenticationEmailSenderTest {
    @Test void should_send_actual_utf8_mime_with_current_email_and_original_expiry() throws Exception {
        var client=mock(JavaMailSender.class); var mime=new MimeMessage(Session.getInstance(new Properties()));
        when(client.createMimeMessage()).thenReturn(mime);
        var expiry=Instant.parse("2026-11-03T12:00:00.123Z");
        new SmtpReauthenticationEmailSender(client,"noreply@example.com").send(new ReauthenticationEmailMessage("alice@example.com","012345",expiry));
        verify(client).send(mime); mime.saveChanges();
        assertEquals("alice@example.com",mime.getAllRecipients()[0].toString()); assertEquals("音乐播放器身份验证验证码",mime.getSubject());
        String text=(String)mime.getContent(); assertTrue(text.contains("012345"));assertTrue(text.contains(expiry.toString()));assertTrue(text.contains("不会直接修改邮箱"));
    }
    @Test void should_translate_smtp_failure_and_redact_envelope_string() {
        var client=mock(JavaMailSender.class);when(client.createMimeMessage()).thenThrow(new MailSendException("sensitive"));
        var envelope=new ReauthenticationEmailMessage("alice@example.com","012345",Instant.now());
        var failure=assertThrows(ReauthenticationEmailSendFailedException.class,()->new SmtpReauthenticationEmailSender(client,"noreply@example.com").send(envelope));
        assertFalse(failure.getMessage().contains("sensitive")); assertFalse(envelope.toString().contains("alice"));assertFalse(envelope.toString().contains("012345"));
    }
}

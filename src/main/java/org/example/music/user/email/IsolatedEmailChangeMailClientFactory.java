package org.example.music.user.email;

import java.util.Properties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
public class IsolatedEmailChangeMailClientFactory {
    private final JavaMailSender source;

    public IsolatedEmailChangeMailClientFactory(JavaMailSender source) { this.source = source; }

    public JavaMailSenderImpl createClient() {
        if (!(source instanceof JavaMailSenderImpl original)) {
            throw new IllegalStateException("Email change requires an isolatable SMTP client");
        }
        var client = new JavaMailSenderImpl();
        client.setHost(original.getHost());
        client.setPort(original.getPort());
        client.setProtocol(original.getProtocol());
        client.setUsername(original.getUsername());
        client.setPassword(original.getPassword());
        client.setDefaultEncoding(original.getDefaultEncoding());
        var properties = new Properties();
        original.getSession().getProperties().stringPropertyNames().forEach(
                name -> properties.setProperty(name, original.getSession().getProperty(name)));
        for (String protocol : new String[]{"smtp", "smtps"}) {
            for (String timeout : new String[]{"connectiontimeout", "timeout", "writetimeout"}) {
                properties.setProperty("mail." + protocol + "." + timeout, "5000");
            }
        }
        properties.setProperty("mail.debug", "false");
        client.setJavaMailProperties(properties);
        return client;
    }
}

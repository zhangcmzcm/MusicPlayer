package org.example.music.user.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.*;

class IsolatedEmailChangeMailClientFactoryTest {
    @Test void should_copy_settings_without_mutating_shared_client_or_session() {
        var original=new JavaMailSenderImpl(); original.setHost("smtp.example.com");original.setPort(465);original.setProtocol("smtps");
        original.setUsername("test-user");original.setPassword("test-secret");original.setDefaultEncoding("UTF-8");
        var props=new Properties();props.setProperty("mail.smtp.auth","true");props.setProperty("mail.smtp.starttls.enable","true");props.setProperty("mail.debug","true");
        original.setJavaMailProperties(props);
        var snapshot=new Properties();snapshot.putAll(props);
        var copy=new IsolatedEmailChangeMailClientFactory(original).createClient();
        assertEquals(original.getHost(),copy.getHost());assertEquals(original.getPort(),copy.getPort());assertEquals(original.getProtocol(),copy.getProtocol());
        assertEquals(original.getUsername(),copy.getUsername());assertEquals(original.getPassword(),copy.getPassword());assertEquals(original.getDefaultEncoding(),copy.getDefaultEncoding());
        assertNotSame(original.getSession(),copy.getSession()); assertNotSame(props,copy.getJavaMailProperties()); assertEquals(snapshot,props);
        for(String protocol:new String[]{"smtp","smtps"})for(String name:new String[]{"connectiontimeout","timeout","writetimeout"})assertEquals("5000",copy.getJavaMailProperties().getProperty("mail."+protocol+"."+name));
        assertEquals("false",copy.getJavaMailProperties().getProperty("mail.debug")); assertFalse(copy.getSession().getDebug());
        assertEquals("true",copy.getJavaMailProperties().getProperty("mail.smtp.auth"));assertEquals("true",copy.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
    }
    @Test void should_refuse_unsupported_shared_sender() {
        assertThrows(IllegalStateException.class,()->new IsolatedEmailChangeMailClientFactory(mock(JavaMailSender.class)).createClient());
    }
}

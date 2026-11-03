package org.example.music.user.email;

public interface ReauthenticationEmailSender {
    void send(ReauthenticationEmailMessage message);
}

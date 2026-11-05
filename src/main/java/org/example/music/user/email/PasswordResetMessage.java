package org.example.music.user.email;
import java.time.Instant;
public record PasswordResetMessage(String email,String code,Instant expiresAt){
 @Override public String toString(){return "PasswordResetMessage[redacted]";}
}

package org.example.music.user.domain;
import java.util.Optional;
public record PasswordResetTask(String challengeDigest,String emailDigest,String email,PasswordResetAdmission admission,Optional<PasswordResetAccountSnapshot> account,String traceId){
 @Override public String toString(){return "PasswordResetTask[redacted]";}
}

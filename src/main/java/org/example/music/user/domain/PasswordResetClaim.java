package org.example.music.user.domain;
public record PasswordResetClaim(PasswordResetTask task,String ownerNonce){
 @Override public String toString(){return "PasswordResetClaim[redacted]";}
}

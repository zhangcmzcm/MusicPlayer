package org.example.music.user.domain;
public record PasswordResetAccountSnapshot(Integer userId,String email,UserRole userRole,long tokenVersion){
 @Override public String toString(){return "PasswordResetAccountSnapshot[redacted]";}
}

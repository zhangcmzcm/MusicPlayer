package org.example.music.user.exception;
public final class PasswordResetDiagnostics {
 private PasswordResetDiagnostics() {}
 public static Throwable sanitized(Throwable failure) {
  if(failure==null)return null;
  var safe=new RuntimeException("Password reset operation failure"); safe.setStackTrace(failure.getStackTrace());return safe;
 }
}

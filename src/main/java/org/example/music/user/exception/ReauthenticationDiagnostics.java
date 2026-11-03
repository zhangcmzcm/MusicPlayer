package org.example.music.user.exception;

public final class ReauthenticationDiagnostics {
    private ReauthenticationDiagnostics() { }
    public static Throwable sanitized(Throwable failure) {
        var diagnostic = new RuntimeException("Reauthentication operation failure");
        if (failure != null) diagnostic.setStackTrace(failure.getStackTrace());
        return diagnostic;
    }
}

package org.example.music.user.exception;

public final class ReauthenticationEmailDiagnostics {
    private ReauthenticationEmailDiagnostics() { }

    public static Throwable sanitized(Throwable failure) {
        var result = new RuntimeException("Reauthentication email operation failure");
        if (failure != null) result.setStackTrace(failure.getStackTrace());
        return result;
    }
}

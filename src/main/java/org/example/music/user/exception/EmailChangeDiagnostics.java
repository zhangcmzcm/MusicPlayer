package org.example.music.user.exception;

public final class EmailChangeDiagnostics {
    private EmailChangeDiagnostics() { }

    public static Throwable sanitized(Throwable failure) {
        var result = new RuntimeException("Email change operation failure");
        if (failure != null) result.setStackTrace(failure.getStackTrace());
        return result;
    }
}

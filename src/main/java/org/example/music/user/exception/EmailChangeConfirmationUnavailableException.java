package org.example.music.user.exception;

public class EmailChangeConfirmationUnavailableException extends RuntimeException {
    public EmailChangeConfirmationUnavailableException(Throwable cause) {
        super("邮箱变更确认暂不可用，请核实当前邮箱后重试", EmailChangeDiagnostics.sanitized(cause));
    }
}

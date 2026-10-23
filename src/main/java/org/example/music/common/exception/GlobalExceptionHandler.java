package org.example.music.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.example.music.email.exception.EmailSendFailedException;
import org.example.music.email.exception.SendCodeRateLimitedException;
import org.example.music.user.exception.UserAlreadyExistsException;
import org.example.music.verification.exception.EmailVerificationCodeException;
import org.example.music.verification.exception.VerificationCodeStoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ProblemDetail problem = problem(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request validation failed",
                "One or more request fields are invalid"
        );
        problem.setProperty("errors", fields);
        return response(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request validation failed",
                "Request body is missing or malformed"
        ));
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleUserConflict(
            UserAlreadyExistsException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.CONFLICT,
                exception.getErrorCode(),
                "User conflict",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(EmailVerificationCodeException.class)
    public ResponseEntity<ProblemDetail> handleVerificationCode(
            EmailVerificationCodeException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = switch (exception.getFailure()) {
            case ALREADY_USED -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.valueOf(422);
            case ATTEMPTS_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
        };
        String code = switch (exception.getFailure()) {
            case ALREADY_USED -> "EMAIL_CODE_ALREADY_USED";
            case INVALID -> "EMAIL_CODE_INVALID";
            case ATTEMPTS_EXCEEDED -> "EMAIL_CODE_ATTEMPTS_EXCEEDED";
        };
        return response(problem(
                request,
                status,
                code,
                "Email verification failed",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(SendCodeRateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleSendCodeRateLimited(
            SendCodeRateLimitedException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = problem(
                request,
                HttpStatus.TOO_MANY_REQUESTS,
                "SEND_CODE_TOO_FREQUENTLY",
                "Verification code rate limited",
                exception.getMessage()
        );
        problem.setProperty("scope", exception.getScope().name());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(EmailSendFailedException.class)
    public ResponseEntity<ProblemDetail> handleEmailSendFailed(
            EmailSendFailedException exception,
            HttpServletRequest request
    ) {
        LOGGER.warn(
                "Email verification code delivery failed, traceId={}, exceptionType={}",
                MDC.get("traceId"),
                exception.getClass().getSimpleName(),
                exception
        );
        return response(problem(
                request,
                HttpStatus.BAD_GATEWAY,
                "EMAIL_SEND_FAILED",
                "Email delivery failed",
                "The email could not be delivered"
        ));
    }

    @ExceptionHandler(VerificationCodeStoreUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleVerificationCodeStoreUnavailable(
            VerificationCodeStoreUnavailableException exception,
            HttpServletRequest request
    ) {
        LOGGER.warn(
                "Email verification code store unavailable, traceId={}, exceptionType={}",
                MDC.get("traceId"),
                exception.getClass().getSimpleName(),
                exception
        );
        return response(problem(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "VERIFICATION_CODE_STORE_UNAVAILABLE",
                "Verification code store unavailable",
                "Email verification is temporarily unavailable"
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        LOGGER.error(
                "Unexpected request failure, traceId={}, exceptionType={}",
                MDC.get("traceId"),
                exception.getClass().getSimpleName(),
                exception
        );
        return response(problem(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal server error",
                "The request could not be completed"
        ));
    }

    private ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private ProblemDetail problem(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String title,
            String detail
    ) {
        ProblemDetail problem = ProblemDetail.forStatus(status.value());
        problem.setType(URI.create("urn:problem:music:" + code.toLowerCase()));
        problem.setTitle(title);
        problem.setDetail(detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        return problem;
    }
}
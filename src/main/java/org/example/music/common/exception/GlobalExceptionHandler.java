package org.example.music.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.example.music.email.exception.EmailSendFailedException;
import org.example.music.storage.exception.AudioObjectNotFoundException;
import org.example.music.storage.exception.AudioStorageUnavailableException;
import org.example.music.track.exception.AudioMetadataInvalidException;
import org.example.music.track.exception.ImportConflictException;
import org.example.music.track.exception.ImportInProgressException;
import org.example.music.track.exception.MediaImportStoreUnavailableException;
import org.example.music.track.exception.MediaObjectCleanupFailedException;
import org.example.music.track.exception.TrackImportRateLimitedException;
import org.example.music.track.exception.UnsupportedAudioFormatException;
import org.example.music.track.exception.TrackDraftValidationException;
import org.example.music.track.exception.TrackNotDraftException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.track.exception.TrackVersionConflictException;
import org.example.music.track.exception.UploadTooLargeException;
import org.example.music.email.exception.SendCodeRateLimitedException;
import org.example.music.user.exception.AdminPrivilegeRevokedException;
import org.example.music.user.exception.AuthStateStoreUnavailableException;
import org.example.music.user.exception.InvalidTokenException;
import org.example.music.user.exception.LastAdminCannotBeDemotedException;
import org.example.music.user.exception.RoleUpdateConflictException;
import org.example.music.user.exception.RoleUpdateForbiddenException;
import org.example.music.user.exception.SelfRoleModificationException;
import org.example.music.user.exception.UserNotFoundException;
import org.example.music.user.exception.InvalidCredentialsException;
import org.example.music.user.exception.LoginRateLimitedException;
import org.example.music.user.exception.LoginTicketInvalidException;
import org.example.music.user.exception.RoleSelectionNotAllowedException;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleHandlerMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Validation failed",
                "Request parameters are invalid"
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Validation failed",
                exception.getMessage()
        ));
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

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials(
            InvalidCredentialsException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Authentication failed",
                "Invalid username, email, password, or verification code"
        ));
    }

    @ExceptionHandler(LoginTicketInvalidException.class)
    public ResponseEntity<ProblemDetail> handleLoginTicketInvalid(
            LoginTicketInvalidException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.UNAUTHORIZED,
                "LOGIN_TICKET_INVALID",
                "Login ticket invalid",
                "Login ticket is invalid, expired, or already used"
        ));
    }

    @ExceptionHandler(RoleSelectionNotAllowedException.class)
    public ResponseEntity<ProblemDetail> handleRoleSelectionNotAllowed(
            RoleSelectionNotAllowedException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.FORBIDDEN,
                "ROLE_SELECTION_NOT_ALLOWED",
                "Role selection denied",
                "Selected login role is not allowed"
        ));
    }

    @ExceptionHandler(LoginRateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleLoginRateLimited(
            LoginRateLimitedException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = problem(
                request,
                HttpStatus.TOO_MANY_REQUESTS,
                "LOGIN_RATE_LIMITED",
                "Login rate limited",
                exception.getMessage()
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(AuthStateStoreUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleAuthStateStoreUnavailable(
            AuthStateStoreUnavailableException exception,
            HttpServletRequest request
    ) {
        LOGGER.warn(
                "Authentication state store unavailable, traceId={}, exceptionType={}",
                MDC.get("traceId"),
                exception.getClass().getSimpleName(),
                exception
        );
        return response(problem(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_STATE_STORE_UNAVAILABLE",
                "Authentication state store unavailable",
                "Login is temporarily unavailable"
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


    @ExceptionHandler(UploadTooLargeException.class)
    public ResponseEntity<ProblemDetail> handleUploadTooLarge(
            UploadTooLargeException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.PAYLOAD_TOO_LARGE,
                "UPLOAD_TOO_LARGE",
                "Audio upload too large",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMultipartTooLarge(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.PAYLOAD_TOO_LARGE,
                "UPLOAD_TOO_LARGE",
                "Audio upload too large",
                "The uploaded file exceeds the configured size limit"
        ));
    }

    @ExceptionHandler(UnsupportedAudioFormatException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedAudio(
            UnsupportedAudioFormatException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_AUDIO_FORMAT",
                "Unsupported audio format",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(AudioMetadataInvalidException.class)
    public ResponseEntity<ProblemDetail> handleAudioMetadataInvalid(
            AudioMetadataInvalidException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.UNPROCESSABLE_ENTITY,
                "AUDIO_METADATA_INVALID",
                "Audio metadata invalid",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(ImportConflictException.class)
    public ResponseEntity<ProblemDetail> handleImportConflict(
            ImportConflictException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.CONFLICT,
                "IMPORT_CONFLICT",
                "Media import conflict",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(ImportInProgressException.class)
    public ResponseEntity<ProblemDetail> handleImportInProgress(
            ImportInProgressException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.CONFLICT,
                "IMPORT_IN_PROGRESS",
                "Media import in progress",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(TrackImportRateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleTrackImportRateLimited(
            TrackImportRateLimitedException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = problem(
                request,
                HttpStatus.TOO_MANY_REQUESTS,
                "TRACK_IMPORT_RATE_LIMITED",
                "Track import rate limited",
                exception.getMessage()
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .body(problem);
    }


    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingRequestParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request validation failed",
                "A required request parameter is missing"
        ));
    }
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ProblemDetail> handleMissingRequestPart(
            MissingServletRequestPartException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request validation failed",
                "A required multipart field is missing"
        ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request validation failed",
                "A request parameter has an invalid format"
        ));
    }

    @ExceptionHandler(MediaImportStoreUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleMediaImportStoreUnavailable(
            MediaImportStoreUnavailableException exception,
            HttpServletRequest request
    ) {
        LOGGER.warn(
                "Media import store unavailable, traceId={}, exceptionType={}",
                MDC.get("traceId"),
                exception.getClass().getSimpleName(),
                exception
        );
        return response(problem(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "MEDIA_IMPORT_STORE_UNAVAILABLE",
                "Media import store unavailable",
                "The media import could not be completed"
        ));
    }

    @ExceptionHandler(AudioStorageUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleAudioStorageUnavailable(
            AudioStorageUnavailableException exception,
            HttpServletRequest request
    ) {
        LOGGER.warn(
                "Audio storage unavailable, traceId={}, exceptionType={}",
                MDC.get("traceId"),
                exception.getClass().getSimpleName(),
                exception
        );
        return response(problem(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "MEDIA_STORAGE_UNAVAILABLE",
                "Media storage unavailable",
                "The media storage service is temporarily unavailable"
        ));
    }

    @ExceptionHandler(MediaObjectCleanupFailedException.class)
    public ResponseEntity<ProblemDetail> handleMediaObjectCleanupFailed(
            MediaObjectCleanupFailedException exception,
            HttpServletRequest request
    ) {
        LOGGER.warn(
                "Media object cleanup failed, traceId={}, exceptionType={}",
                MDC.get("traceId"),
                exception.getClass().getSimpleName(),
                exception
        );
        return response(problem(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "MEDIA_OBJECT_CLEANUP_FAILED",
                "Media object cleanup failed",
                "The imported media object could not be cleaned up"
        ));
    }

    @ExceptionHandler(AudioObjectNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleAudioObjectNotFound(
            AudioObjectNotFoundException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.NOT_FOUND,
                "TRACK_NOT_FOUND",
                "Track not found",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "Access denied",
                "You do not have permission to perform this operation"
        ));
    }

    @ExceptionHandler(TrackDraftValidationException.class)
    public ResponseEntity<ProblemDetail> handleTrackDraftValidation(
            TrackDraftValidationException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "请求校验失败",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(TrackNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleTrackNotFound(
            TrackNotFoundException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.NOT_FOUND,
                "TRACK_NOT_FOUND",
                "歌曲不存在",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(TrackNotDraftException.class)
    public ResponseEntity<ProblemDetail> handleTrackNotDraft(
            TrackNotDraftException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.CONFLICT,
                "TRACK_NOT_DRAFT",
                "歌曲不是草稿",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(TrackVersionConflictException.class)
    public ResponseEntity<ProblemDetail> handleTrackVersionConflict(
            TrackVersionConflictException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = problem(
                request,
                HttpStatus.CONFLICT,
                "TRACK_VERSION_CONFLICT",
                "歌曲版本冲突",
                exception.getMessage()
        );
        problem.setProperty("expectedVersion", exception.getExpectedVersion());
        problem.setProperty("actualVersion", exception.getActualVersion());
        return response(problem);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(
            ObjectOptimisticLockingFailureException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.CONFLICT,
                "TRACK_VERSION_CONFLICT",
                "歌曲版本冲突",
                "歌曲已被其他操作修改，请重新读取后重试"
        ));
    }

    @ExceptionHandler(TrackStoreUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleTrackStoreUnavailable(
            TrackStoreUnavailableException exception,
            HttpServletRequest request
    ) {
        LOGGER.warn(
                "Track store unavailable, traceId={}, exceptionType={}",
                MDC.get("traceId"),
                exception.getClass().getSimpleName(),
                exception
        );
        return response(problem(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "TRACK_STORE_UNAVAILABLE",
                "歌曲存储不可用",
                "歌曲草稿暂时无法更新"
        ));
    }
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleUserNotFound(
            UserNotFoundException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND",
                "User not found",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(SelfRoleModificationException.class)
    public ResponseEntity<ProblemDetail> handleSelfRoleModification(
            SelfRoleModificationException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.FORBIDDEN,
                "CANNOT_MODIFY_SELF_ROLE",
                "Role modification denied",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(RoleUpdateForbiddenException.class)
    public ResponseEntity<ProblemDetail> handleRoleUpdateForbidden(
            RoleUpdateForbiddenException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "Access denied",
                exception.getMessage()
        ));
    }
    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidToken(
            InvalidTokenException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.UNAUTHORIZED,
                "INVALID_TOKEN",
                "Invalid token",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(AdminPrivilegeRevokedException.class)
    public ResponseEntity<ProblemDetail> handleAdminPrivilegeRevoked(
            AdminPrivilegeRevokedException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.FORBIDDEN,
                "ADMIN_PRIVILEGE_REVOKED",
                "Administrator privilege revoked",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(LastAdminCannotBeDemotedException.class)
    public ResponseEntity<ProblemDetail> handleLastAdminCannotBeDemoted(
            LastAdminCannotBeDemotedException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.CONFLICT,
                "LAST_ADMIN_CANNOT_BE_DEMOTED",
                "Last administrator protected",
                exception.getMessage()
        ));
    }

    @ExceptionHandler(RoleUpdateConflictException.class)
    public ResponseEntity<ProblemDetail> handleRoleUpdateConflict(
            RoleUpdateConflictException exception,
            HttpServletRequest request
    ) {
        return response(problem(
                request,
                HttpStatus.CONFLICT,
                "ROLE_UPDATE_CONFLICT",
                "Role update conflict",
                exception.getMessage()
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
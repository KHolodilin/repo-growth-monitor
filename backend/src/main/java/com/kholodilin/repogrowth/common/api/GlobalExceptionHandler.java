package com.kholodilin.repogrowth.common.api;

import com.kholodilin.repogrowth.github.exception.GitHubException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        log.warn("API error category={} message={}", ex.code(), ex.getMessage());
        return ResponseEntity.status(ex.status()).body(error(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(GitHubException.class)
    public ResponseEntity<ApiError> handleGitHub(GitHubException ex) {
        log.warn("GitHub error category={} status={} retryable={} message={}",
                ex.errorCode(), ex.statusCode(), ex.retryable(), ex.getMessage());
        HttpStatus status = switch (ex.errorCode()) {
            case GITHUB_AUTH_ERROR -> HttpStatus.UNAUTHORIZED;
            case GITHUB_RATE_LIMIT_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(error(ex.errorCode(), ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiError> handleValidation(Exception ex) {
        return ResponseEntity.badRequest().body(error(ErrorCode.VALIDATION_ERROR, ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(Exception ex) {
        return ResponseEntity.badRequest().body(error(ErrorCode.VALIDATION_ERROR, "Invalid request body"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error path={}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(ErrorCode.INTERNAL_ERROR, "Internal server error"));
    }

    private ApiError error(ErrorCode code, String message) {
        return new ApiError(code, message, Instant.now(), MDC.get("traceId"));
    }
}

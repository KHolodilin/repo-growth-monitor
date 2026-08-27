package com.kholodilin.repogrowth.github.exception;

import com.kholodilin.repogrowth.common.api.ErrorCode;

import java.time.Instant;

public class GitHubException extends RuntimeException {

    private final ErrorCode errorCode;
    private final int statusCode;
    private final boolean retryable;
    private final Instant rateLimitReset;

    public GitHubException(
            ErrorCode errorCode,
            int statusCode,
            boolean retryable,
            Instant rateLimitReset,
            String message
    ) {
        super(message);
        this.errorCode = errorCode;
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.rateLimitReset = rateLimitReset;
    }

    public GitHubException(
            ErrorCode errorCode,
            int statusCode,
            boolean retryable,
            Instant rateLimitReset,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.errorCode = errorCode;
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.rateLimitReset = rateLimitReset;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public Instant rateLimitReset() {
        return rateLimitReset;
    }

    public static GitHubException auth(int status, String message) {
        return new GitHubException(ErrorCode.GITHUB_AUTH_ERROR, status, false, null, message);
    }

    public static GitHubException rateLimit(Instant reset, String message) {
        return new GitHubException(ErrorCode.GITHUB_RATE_LIMIT_EXCEEDED, 403, true, reset, message);
    }

    public static GitHubException notFound(String message) {
        return new GitHubException(ErrorCode.NOT_FOUND, 404, false, null, message);
    }

    public static GitHubException validation(String message) {
        return new GitHubException(ErrorCode.VALIDATION_ERROR, 422, false, null, message);
    }

    public static GitHubException api(int status, boolean retryable, Instant reset, String message) {
        return new GitHubException(ErrorCode.GITHUB_API_ERROR, status, retryable, reset, message);
    }

    public static GitHubException timeout(Throwable cause) {
        return new GitHubException(
                ErrorCode.GITHUB_API_ERROR,
                0,
                true,
                null,
                "GitHub API timeout",
                cause
        );
    }

    public static GitHubException malformed(Throwable cause) {
        return new GitHubException(
                ErrorCode.GITHUB_API_ERROR,
                200,
                false,
                null,
                "Malformed GitHub API payload",
                cause
        );
    }

    public static GitHubException network(Throwable cause) {
        return new GitHubException(
                ErrorCode.GITHUB_API_ERROR,
                0,
                true,
                null,
                "Temporary GitHub network error",
                cause
        );
    }
}

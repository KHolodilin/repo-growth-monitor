package com.kholodilin.collector.credentials;
/** Sanitized boundary exception: never attach a response body or credential-bearing cause. */
public final class CredentialUnavailableException extends RuntimeException {
    public CredentialUnavailableException() {super("GitHub credential is unavailable");}
}

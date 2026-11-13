package com.kholodilin.collector.queue;
/** Durable attempt identity remains authoritative after replay records expire. */
public final class AttemptConflictException extends IllegalArgumentException {
    public AttemptConflictException() {super("Attempt already accepted with different content");}
}

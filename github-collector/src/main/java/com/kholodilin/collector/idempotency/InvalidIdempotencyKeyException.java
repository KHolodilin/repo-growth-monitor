package com.kholodilin.collector.idempotency;
public final class InvalidIdempotencyKeyException extends IllegalArgumentException {
    public InvalidIdempotencyKeyException() {super("Idempotency-Key must contain 1..128 ASCII token characters");}
}

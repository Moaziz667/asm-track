package com.asm.delivery.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a write that must act once even if it is received several times — see
 * {@link IdempotencyAspect}.
 *
 * <p>Deliberately without options. How long an answer stays replayable is a property of the
 * platform, not of one endpoint: it is the driver app's offline-queue TTL, and the two must agree
 * or a write the phone still intends to replay would no longer be recognised. It is therefore
 * configured once, in {@link ProcessedRequestCleanupJob}
 * ({@code idempotency.cleanup.ttl-hours}), rather than repeated on the 26 annotated methods, where
 * it would drift.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentOperation {
}

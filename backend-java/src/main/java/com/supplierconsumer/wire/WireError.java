package com.supplierconsumer.wire;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares how an unhandled exception in this handler (or controller) should be reported.
 *
 * <p>The Node code is not consistent about this, and the inconsistency is observable. Chat
 * handlers always attach the raw exception text under an {@code error} key and each uses its own
 * fallback message ({@code "Failed to fetch messages"}, {@code "Failed to send message"}, ...).
 * Product and order handlers attach {@code error} only when {@code NODE_ENV === 'development'}.
 * Everything else returns a bare {@code "Internal server error"}.
 *
 * <p>Rather than scatter that through the controllers, each one declares its policy and
 * {@link WireExceptionHandler} reads it off the matched handler method.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface WireError {

    /** The message returned when the handler throws something unexpected. */
    String message() default "Internal server error";

    /** Whether to include the raw exception text under an {@code error} key. */
    ErrorDetail detail() default ErrorDetail.NEVER;

    enum ErrorDetail {
        /** Never expose the exception text. */
        NEVER,
        /** Expose it only under the dev profile, matching {@code NODE_ENV === 'development'}. */
        DEV_ONLY,
        /** Always expose it -- what the chat controller does today. */
        ALWAYS
    }
}

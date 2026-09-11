package com.supplierconsumer.wire;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An error the handler meant to return, carrying the status and the exact message the Node
 * version produced.
 *
 * <p>The message is not incidental prose. The frontend passes it straight to i18next as a lookup
 * key ({@code t(msg, { defaultValue: msg })}), so strings like
 * {@code "Wait until the admin approves you."} have to match character for character or the
 * translation silently falls back to English.
 *
 * <p>{@code extras} covers the endpoints that add keys beside {@code success} and {@code message}:
 * a blocked login adds {@code companyStatus} and {@code rejectionMessage}, and a stock failure on
 * order acceptance adds the machine-readable {@code insufficientStock} array.
 *
 * <p>Deliberately not {@code ResponseStatusException}: that renders as RFC-7807 ProblemDetail,
 * which is a different shape entirely.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final transient Map<String, Object> extras = new LinkedHashMap<>();

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException with(String key, Object value) {
        extras.put(key, value);
        return this;
    }

    public int status() {
        return status;
    }

    public Map<String, Object> extras() {
        return extras;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }
}

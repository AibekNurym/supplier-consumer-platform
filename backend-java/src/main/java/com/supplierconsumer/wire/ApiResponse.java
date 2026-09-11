package com.supplierconsumer.wire;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code {success, message, data}} envelope -- built as an ordered map rather than modelled
 * as a fixed type, because in this API neither the set of keys nor their order is constant.
 *
 * <p>Most handlers emit all three keys. Product create, read and update omit {@code message};
 * several mutations omit {@code data}; {@code DELETE /api/products/:id} emits them as
 * {@code success, data, message}, in that order. On top of that a handful of endpoints add keys
 * of their own outside the envelope: {@code stockUpdated}, {@code alreadyAccepted},
 * {@code stockRestored}, {@code chatMessage}, {@code insufficientStock}, and on a rejected login
 * {@code companyStatus} and {@code rejectionMessage}.
 *
 * <p>Because the backing map preserves insertion order, builder call order is JSON key order.
 * That turns all of the above from special cases into ordinary usage:
 *
 * <pre>{@code
 * ApiResponse.ok().data(row).message("Product deleted successfully");
 * ApiResponse.ok().message("Order accepted and stock updated").put("stockUpdated", true);
 * }</pre>
 *
 * <p>{@code message} and {@code data} are skipped when null, which is how handlers omit them.
 * {@link #put} always writes, so an intentional null extra key still appears.
 */
public final class ApiResponse {

    private final Map<String, Object> body = new LinkedHashMap<>();

    private ApiResponse(boolean success) {
        body.put("success", success);
    }

    public static ApiResponse ok() {
        return new ApiResponse(true);
    }

    public static ApiResponse fail() {
        return new ApiResponse(false);
    }

    public ApiResponse message(String message) {
        return putIfPresent("message", message);
    }

    public ApiResponse data(Object data) {
        return putIfPresent("data", data);
    }

    /** Adds a key outside the envelope, at the current position. */
    public ApiResponse put(String key, Object value) {
        body.put(key, value);
        return this;
    }

    private ApiResponse putIfPresent(String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
        return this;
    }

    @JsonValue
    public Map<String, Object> body() {
        return body;
    }
}

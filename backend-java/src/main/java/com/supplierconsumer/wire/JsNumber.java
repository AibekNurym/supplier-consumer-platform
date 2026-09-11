package com.supplierconsumer.wire;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * A double that must be written the way {@code JSON.stringify} would write it.
 *
 * <p>This exists for values that reached the Node response as JavaScript numbers rather than
 * strings -- principally numerics nested inside a {@code jsonb} column, which Postgres stores
 * textually as {@code 1200.00} but {@code JSON.parse} turns into the number {@code 1200}.
 * Jackson would render a Java double as {@code 1200.0}, which matches neither.
 *
 * <p>Note this is not interchangeable with a column-level numeric: {@code unit_price} selected
 * as a column is the string {@code "1200.00"}, while the same value read out of a
 * {@code JSONB_BUILD_OBJECT} is the number {@code 1200}. Both appear in this API.
 */
@JsonSerialize(using = JsNumberSerializer.class)
public record JsNumber(double value) {

    public static JsNumber of(double v) {
        return new JsNumber(v);
    }
}

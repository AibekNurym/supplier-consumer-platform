package com.supplierconsumer.wire;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Arithmetic helpers that have to agree with JavaScript rather than with Java convention.
 *
 * <p>Cart totals, checkout amounts and the currency figures inside the generated chat summaries
 * were all produced by {@code Number.prototype.toFixed(2)} over a double accumulated with
 * {@code parseFloat}. They reach clients as strings, so reproducing the rounding exactly matters.
 */
public final class JsNumbers {

    private JsNumbers() {
    }

    /**
     * The equivalent of {@code value.toFixed(digits)}.
     *
     * <p>ECMAScript rounds the <em>exact binary value</em> of the double, half away from zero.
     * {@code new BigDecimal(double)} is the constructor that preserves that exact value;
     * {@code BigDecimal.valueOf} would first go through {@code Double.toString} and round the
     * decimal shorthand instead. The difference is visible: {@code 1.005} is really
     * {@code 1.00499999999999989...}, so JavaScript yields {@code "1.00"} and the shorthand
     * route would wrongly yield {@code "1.01"}.
     */
    public static String toFixed(double value, int digits) {
        if (!Double.isFinite(value)) {
            return "NaN";
        }
        return new BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString();
    }

    public static String toFixed2(double value) {
        return toFixed(value, 2);
    }

    /**
     * Parses a value the driver handed over as a string back into a double, matching
     * {@code parseFloat}. Used where the Node code did arithmetic on a numeric column.
     */
    public static double parseFloat(Object value) {
        if (value == null) {
            return 0d;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    /** {@code parseInt} semantics for the same reason. */
    public static int parseInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Converts a computed double to the {@link BigDecimal} to store in a numeric column.
     * {@code valueOf} is right here and wrong in {@link #toFixed}: it routes through
     * {@code Double.toString}, giving the same digits JavaScript would have sent to the database.
     */
    public static BigDecimal forStorage(double value) {
        return BigDecimal.valueOf(value);
    }
}

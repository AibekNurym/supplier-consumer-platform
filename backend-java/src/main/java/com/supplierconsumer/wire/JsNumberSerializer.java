package com.supplierconsumer.wire;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigInteger;

/**
 * Writes a double using ECMAScript's Number-to-String rules, so the output matches what
 * {@code JSON.stringify} produced in the Node backend.
 *
 * <p>The two rules that matter in practice: an integral value loses its fractional part
 * ({@code 1200.0} becomes {@code 1200}, not {@code 1200.0}), and a non-finite value becomes
 * {@code null} rather than failing, because {@code JSON.stringify(NaN)} is {@code "null"}.
 */
public class JsNumberSerializer extends JsonSerializer<JsNumber> {

    @Override
    public void serialize(JsNumber n, JsonGenerator gen, SerializerProvider provider) throws IOException {
        double d = n.value();

        if (!Double.isFinite(d)) {
            gen.writeNull();
            return;
        }

        if (d == Math.rint(d) && Math.abs(d) < 1e21) {
            gen.writeNumber(BigInteger.valueOf((long) d).toString());
            return;
        }

        // Double.toString has produced the shortest round-tripping representation since JDK 19,
        // which is the same thing ECMAScript specifies. The exponent marker still differs
        // (Java writes E, JavaScript writes e+), and that only shows up past 1e21 -- far outside
        // the range of anything this schema stores.
        gen.writeNumber(Double.toString(d).replace("E", "e+").replace("e+-", "e-"));
    }
}

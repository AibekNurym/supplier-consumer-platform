package com.supplierconsumer.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@code json}/{@code jsonb} column into the plain Java equivalent of what
 * {@code JSON.parse} handed the Node code.
 *
 * <p>The interesting part is numbers. Postgres keeps {@code 1200.00} textually inside jsonb,
 * but {@code JSON.parse} produces the JavaScript number {@code 1200} and {@code JSON.stringify}
 * then writes {@code 1200}. Mapping fractional values to {@link JsNumber} reproduces that.
 * Integers are left as Java integers -- JavaScript would render them identically.
 */
public final class JsJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsJson() {
    }

    public static Object parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return convert(MAPPER.readTree(raw));
        } catch (Exception e) {
            // A column that will not parse as JSON is not something the Node code could have
            // produced either; surfacing the raw text beats throwing inside a row mapper.
            return raw;
        }
    }

    public static Object convert(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(), convert(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>(node.size());
            node.forEach(child -> out.add(convert(child)));
            return out;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToInt() ? (Object) node.asInt() : (Object) node.asLong();
        }
        if (node.isNumber()) {
            return JsNumber.of(node.asDouble());
        }
        return node.asText();
    }
}

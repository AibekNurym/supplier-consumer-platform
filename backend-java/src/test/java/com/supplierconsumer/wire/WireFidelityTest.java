package com.supplierconsumer.wire;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the parts of the wire contract that are easy to "fix" by accident. Each case here
 * corresponds to something the Node backend did that a straightforward Java port gets wrong.
 */
class WireFidelityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PgJson pgJson = new PgJson(ZoneId.of("Asia/Almaty"));

    @Test
    @DisplayName("a timestamp is rendered by reading the wall clock in the configured zone")
    void timestampUsesWireZone() {
        // Stored value carries no zone. Node read it as local time and printed the UTC instant,
        // so in a +05 zone the rendered hour is five behind the stored one.
        String rendered = pgJson.jsDate(LocalDateTime.of(2025, 1, 15, 10, 23, 45));

        assertThat(rendered).isEqualTo("2025-01-15T05:23:45.000Z");
    }

    @Test
    @DisplayName("sub-millisecond precision is truncated, not rounded")
    void timestampTruncatesMicroseconds() {
        // Postgres keeps microseconds; a JavaScript Date holds milliseconds.
        LocalDateTime withMicros = LocalDateTime.of(2025, 1, 15, 10, 0, 0, 123_999_000);

        assertThat(pgJson.jsDate(withMicros)).isEqualTo("2025-01-15T05:00:00.123Z");
    }

    @Test
    @DisplayName("a numeric inside jsonb loses its trailing zeros, unlike the same column selected directly")
    void jsonbNumericBecomesAJavaScriptNumber() throws Exception {
        // GET /api/orders/consumer selects unit_price as a column, so it is the string "1200.00".
        // GET /api/issues/:id wraps it in JSONB_BUILD_OBJECT, and JSON.parse makes it the number
        // 1200. Same column, two endpoints, two types -- both have to be reproduced.
        Object fromJsonb = JsJson.parse("{\"unit_price\": 1200.00}");

        assertThat(mapper.writeValueAsString(fromJsonb)).isEqualTo("{\"unit_price\":1200}");
    }

    @Test
    @DisplayName("a fractional jsonb number keeps its fraction")
    void jsonbFractionalNumber() throws Exception {
        Object parsed = JsJson.parse("{\"unit_price\": 1200.50}");

        assertThat(mapper.writeValueAsString(parsed)).isEqualTo("{\"unit_price\":1200.5}");
    }

    @Test
    @DisplayName("toFixed rounds the exact binary double, the way ECMAScript specifies")
    void toFixedMatchesJavaScript() {
        // 1.005 is really 1.00499999999999989..., so JavaScript yields "1.00". Rounding the
        // decimal shorthand instead (BigDecimal.valueOf) would wrongly give "1.01".
        assertThat(JsNumbers.toFixed2(1.005)).isEqualTo("1.00");
        assertThat(JsNumbers.toFixed2(2.675)).isEqualTo("2.67");
        assertThat(JsNumbers.toFixed2(1200)).isEqualTo("1200.00");
        assertThat(JsNumbers.toFixed2(0.1 + 0.2)).isEqualTo("0.30");
    }

    @Test
    @DisplayName("the envelope keeps keys in the order the handler wrote them")
    void envelopePreservesKeyOrder() throws Exception {
        // DELETE /api/products/:id is the odd one out: data comes before message.
        ApiResponse deleted = ApiResponse.ok().data(Map.of("id", 1)).message("Product deleted successfully");

        assertThat(mapper.writeValueAsString(deleted))
                .isEqualTo("{\"success\":true,\"data\":{\"id\":1},\"message\":\"Product deleted successfully\"}");
    }

    @Test
    @DisplayName("a handler that supplies no data omits the key entirely")
    void envelopeOmitsAbsentKeys() throws Exception {
        ApiResponse response = ApiResponse.ok().message("Item removed from cart").data(null);

        assertThat(mapper.writeValueAsString(response))
                .isEqualTo("{\"success\":true,\"message\":\"Item removed from cart\"}");
    }

    @Test
    @DisplayName("endpoints can add keys outside the envelope")
    void envelopeCarriesExtraTopLevelKeys() throws Exception {
        ApiResponse accepted = ApiResponse.ok()
                .message("Order accepted and stock updated")
                .put("stockUpdated", true);

        assertThat(mapper.writeValueAsString(accepted))
                .isEqualTo("{\"success\":true,\"message\":\"Order accepted and stock updated\",\"stockUpdated\":true}");
    }

    @Test
    @DisplayName("a null field is still written, because JSON.stringify only drops undefined")
    void nullsAreEmitted() throws Exception {
        // Clients test for keys like phone and readAt, so an inclusion rule that dropped nulls
        // would quietly change the shape of almost every response.
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", 1);
        row.put("phone", null);

        assertThat(mapper.writeValueAsString(row)).isEqualTo("{\"id\":1,\"phone\":null}");
    }

    @Test
    @DisplayName("an integral JavaScript number drops its decimal point")
    void jsNumberFormatting() throws Exception {
        assertThat(mapper.writeValueAsString(JsNumber.of(1200.0))).isEqualTo("1200");
        assertThat(mapper.writeValueAsString(JsNumber.of(1200.5))).isEqualTo("1200.5");
        assertThat(mapper.writeValueAsString(JsNumber.of(Double.NaN))).isEqualTo("null");
    }
}

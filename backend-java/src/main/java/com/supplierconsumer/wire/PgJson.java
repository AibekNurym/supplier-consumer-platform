package com.supplierconsumer.wire;

import org.springframework.jdbc.core.RowMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reproduces the type mapping the Node backend's Postgres driver applied, so that a row read
 * here serializes to exactly the JSON the Express handlers returned.
 *
 * <p>This class carries most of the API's wire contract. Roughly two thirds of the endpoints
 * simply handed the driver's output to {@code res.json()}, which means the driver's quirks
 * <em>are</em> the contract:
 *
 * <ul>
 *   <li>{@code numeric} and {@code int8} arrive as <strong>strings</strong>. So {@code price}
 *       is {@code "1200.00"} and a bare {@code COUNT(*)} is {@code "7"}. An explicit
 *       {@code COUNT(*)::INT} comes back as {@code int4} and stays a number -- which is why
 *       {@code unreadCount} is a number while {@code product_count} is a string.</li>
 *   <li>Keys come from the column label, so the SQL alias decides the casing. Nothing here
 *       needs to know that {@code first_name} is snake_case while a hand-mapped endpoint
 *       returns {@code firstName}.</li>
 *   <li>Timestamps are the subtle one -- see {@link #jsDate}.</li>
 * </ul>
 *
 * <p>Endpoints whose response the Node code assembled by hand (login, register, the notification
 * feed, cart totals) do not go through here; they have explicit DTOs instead.
 */
public class PgJson {

    /**
     * {@code JSON.stringify} on a JavaScript Date yields ISO-8601 in UTC with exactly three
     * fractional digits.
     */
    private static final DateTimeFormatter JS_ISO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneId.of("UTC"));

    private final ZoneId wireZone;

    public PgJson(ZoneId wireZone) {
        this.wireZone = wireZone;
    }

    /** A mapper for the common case: one row becomes one JSON object. */
    public RowMapper<Map<String, Object>> rowMapper() {
        return (rs, rowNum) -> row(rs);
    }

    public Map<String, Object> row(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int columns = md.getColumnCount();
        Map<String, Object> out = LinkedHashMap.newLinkedHashMap(columns);
        for (int i = 1; i <= columns; i++) {
            out.put(md.getColumnLabel(i), value(rs, md, i));
        }
        return out;
    }

    private Object value(ResultSet rs, ResultSetMetaData md, int i) throws SQLException {
        // Switching on the JDBC type code rather than the Postgres type name, because the name is
        // not stable: the driver reports "serial" instead of "int4" for any column carrying a
        // sequence default, so matching on names silently sends every primary key down the wrong
        // branch. The code is 4 (INTEGER) either way.
        return switch (md.getColumnType(i)) {
            // Neither of these fits a double without loss, so the driver handed them to JavaScript
            // as strings. That is why price is "1200.00" and a bare COUNT(*) is "7", while an
            // explicit COUNT(*)::INT is the number 7.
            case Types.NUMERIC, Types.DECIMAL, Types.BIGINT -> rs.getString(i);

            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> {
                int v = rs.getInt(i);
                yield rs.wasNull() ? null : v;
            }
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> {
                double v = rs.getDouble(i);
                yield rs.wasNull() ? null : JsNumber.of(v);
            }
            case Types.BOOLEAN, Types.BIT -> {
                boolean v = rs.getBoolean(i);
                yield rs.wasNull() ? null : v;
            }
            case Types.TIMESTAMP -> jsDate(rs.getObject(i, LocalDateTime.class));
            case Types.TIMESTAMP_WITH_TIMEZONE -> {
                OffsetDateTime v = rs.getObject(i, OffsetDateTime.class);
                yield v == null ? null : JS_ISO.format(v.toInstant().truncatedTo(ChronoUnit.MILLIS));
            }
            case Types.DATE -> {
                LocalDate v = rs.getObject(i, LocalDate.class);
                yield v == null ? null : jsDate(v.atStartOfDay());
            }
            case Types.ARRAY -> jsonArray(rs.getArray(i));

            // json and jsonb both arrive as OTHER, alongside inet, point and the rest, which the
            // driver passed through to JavaScript as plain strings.
            case Types.OTHER -> {
                String typeName = md.getColumnTypeName(i);
                yield "json".equals(typeName) || "jsonb".equals(typeName)
                        ? JsJson.parse(rs.getString(i))
                        : rs.getString(i);
            }

            default -> rs.getString(i);
        };
    }

    /**
     * Postgres arrays. The one that matters is the jsonb array produced by
     * {@code ARRAY_AGG(JSONB_BUILD_OBJECT(...))} in the issue-detail query, whose elements have
     * to be parsed so that numerics inside them behave like JavaScript numbers.
     */
    private Object jsonArray(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        String elementType = array.getBaseTypeName();
        Object[] elements = (Object[]) array.getArray();
        List<Object> out = new ArrayList<>(elements.length);
        for (Object element : elements) {
            if (element == null) {
                out.add(null);
            } else if ("json".equals(elementType) || "jsonb".equals(elementType)) {
                out.add(JsJson.parse(element.toString()));
            } else {
                out.add(element);
            }
        }
        return out;
    }

    /**
     * Renders a {@code timestamp without time zone} the way the Node process did.
     *
     * <p>The driver built a JavaScript Date by interpreting the stored wall-clock value in the
     * server's local time zone; {@code JSON.stringify} then wrote that instant in UTC. So a row
     * holding {@code 2025-01-15 10:23:45}, on a server running in Almaty, went out as
     * {@code "2025-01-15T05:23:45.000Z"} -- the value on the wire depends on the server's zone,
     * not just on the stored data.
     *
     * <p>That makes the zone part of the API contract, which is why it is configured explicitly
     * rather than inherited from the host. Postgres stores microseconds and JavaScript Dates hold
     * milliseconds, so the extra precision is truncated, not rounded.
     */
    public String jsDate(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        Instant instant = value.atZone(wireZone).toInstant().truncatedTo(ChronoUnit.MILLIS);
        return JS_ISO.format(instant);
    }
}

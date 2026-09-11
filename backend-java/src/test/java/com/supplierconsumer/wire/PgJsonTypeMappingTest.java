package com.supplierconsumer.wire;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the row mapper against a real Postgres, because the mapping depends on what the JDBC
 * driver reports and that cannot be verified with hand-built fixtures.
 *
 * <p>This exists because of a concrete bug: the driver reports a column's type name as
 * {@code "serial"} rather than {@code "int4"} whenever it carries a sequence default. An earlier
 * version matched on type names and therefore sent every primary key down the string branch, so
 * ids came back as {@code "1"} instead of {@code 1}. Matching on JDBC type codes fixes it, and
 * these cases keep it fixed.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgJsonTypeMappingTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private final PgJson pgJson = new PgJson(ZoneId.of("Asia/Almaty"));

    @BeforeAll
    void createFixture() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE sample (
                        id SERIAL PRIMARY KEY,
                        big BIGSERIAL,
                        qty INT NOT NULL,
                        price DECIMAL(15,2) NOT NULL,
                        active BOOLEAN,
                        payload JSONB,
                        created_at TIMESTAMP,
                        note TEXT
                    )
                    """);
            s.execute("""
                    INSERT INTO sample (qty, price, active, payload, created_at, note)
                    VALUES (7, 1200.00, true, '{"unit_price": 1200.00}'::jsonb,
                            TIMESTAMP '2025-01-15 10:23:45', 'hello')
                    """);
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Map<String, Object> queryOne(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(sql);
            assertThat(rs.next()).isTrue();
            return pgJson.row(rs);
        }
    }

    @Test
    @DisplayName("a SERIAL primary key is a number, despite the driver naming its type 'serial'")
    void serialIsANumber() throws Exception {
        Map<String, Object> row = queryOne("SELECT id, qty FROM sample");

        assertThat(row.get("id")).isInstanceOf(Integer.class).isEqualTo(1);
        assertThat(row.get("qty")).isInstanceOf(Integer.class).isEqualTo(7);
    }

    @Test
    @DisplayName("BIGSERIAL and BIGINT are strings, like every other 64-bit integer")
    void bigintIsAString() throws Exception {
        Map<String, Object> row = queryOne("SELECT big FROM sample");

        assertThat(row.get("big")).isInstanceOf(String.class).isEqualTo("1");
    }

    @Test
    @DisplayName("a DECIMAL keeps its scale, as a string")
    void decimalIsAString() throws Exception {
        Map<String, Object> row = queryOne("SELECT price FROM sample");

        assertThat(row.get("price")).isEqualTo("1200.00");
    }

    @Test
    @DisplayName("a bare COUNT(*) is a string but COUNT(*)::INT is a number")
    void countDependsOnTheCast() throws Exception {
        // This is why unreadCount is a number while product_count is a string: one query casts.
        Map<String, Object> row = queryOne("SELECT count(*) AS c, count(*)::INT AS ci FROM sample");

        assertThat(row.get("c")).isInstanceOf(String.class).isEqualTo("1");
        assertThat(row.get("ci")).isInstanceOf(Integer.class).isEqualTo(1);
    }

    @Test
    @DisplayName("a numeric inside jsonb becomes a JavaScript number and loses its trailing zeros")
    void jsonbNumericDiffersFromTheColumn() throws Exception {
        Map<String, Object> row = queryOne("SELECT price, payload FROM sample");

        assertThat(row.get("price")).isEqualTo("1200.00");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) row.get("payload");
        assertThat(payload.get("unit_price")).isEqualTo(JsNumber.of(1200.0));
    }

    @Test
    @DisplayName("a timestamp is rendered in the configured zone")
    void timestampUsesWireZone() throws Exception {
        Map<String, Object> row = queryOne("SELECT created_at FROM sample");

        assertThat(row.get("created_at")).isEqualTo("2025-01-15T05:23:45.000Z");
    }

    @Test
    @DisplayName("keys come from the column label, so the SQL alias sets the casing")
    void keysFollowTheAlias() throws Exception {
        Map<String, Object> row = queryOne("SELECT note AS sender_name, qty AS available_quantity FROM sample");

        assertThat(row).containsOnlyKeys("sender_name", "available_quantity");
    }

    @Test
    @DisplayName("booleans, text and nulls pass through unchanged")
    void simpleTypes() throws Exception {
        Map<String, Object> row = queryOne("SELECT active, note, NULL::INT AS missing FROM sample");

        assertThat(row.get("active")).isEqualTo(true);
        assertThat(row.get("note")).isEqualTo("hello");
        assertThat(row.get("missing")).isNull();
        assertThat(row).containsKey("missing");
    }
}

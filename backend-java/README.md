# Java backend

A replacement for the Node/Express backend in `../backend`, on Java 21 and Spring Boot 3.5.

It is a drop-in: same paths, same JSON keys, same status codes. The React console in
`../frontend` runs against it unchanged, and any consumer client living outside this
repository should too.

## Running it

```bash
docker compose up -d                 # postgres on 5433, redis on 6380

export JWT_SECRET='a-secret-of-at-least-32-bytes-length!!'
export JWT_REFRESH_SECRET='another-secret-of-at-least-32-bytes!!'
mvn spring-boot:run
```

The service listens on **port 3000**, the port the Node server used, so
`frontend/vite.config.js` proxies to it without any change. Socket.IO runs on its own
port (3001 by default).

To point it at an existing database that already has the Node-built schema, set
`FLYWAY_BASELINE=true` on the first run. That records V1 as already applied so only the
RBAC seed and the schema fixes run.

### Configuration

| Variable | Default | Notes |
|---|---|---|
| `PORT` | `3000` | Matches the Node server so the frontend proxy needs no edit |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5433/scp` | |
| `PGUSER`, `PGPASSWORD` | `scp` / `scp` | |
| `JWT_SECRET`, `JWT_REFRESH_SECRET` | **required** | At least 32 bytes; startup fails without them |
| `WIRE_TIMEZONE` | `Asia/Almaty` | Part of the API contract — see below |
| `REDIS_HOST`, `REDIS_PORT` | `localhost` / `6380` | Optional; the app degrades if absent |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | `admin@platform.com` / generated | A generated password is logged once at startup |
| `SOCKETIO_ENABLED`, `SOCKETIO_PORT` | `true` / `3001` | |
| `RATE_LIMIT_ENABLED` | `false` | Off by default, matching current behaviour |
| `FLYWAY_BASELINE` | `false` | Set true once when adopting an existing database |

Two things have no default on purpose. The JWT secrets are required because the Node code
falls back to a literal that is committed to this repository, so every token it signs is
forgeable by anyone who has read it. And a generated admin password is logged rather than
shipped, because the Node bootstrap hardcodes one, meaning every deployment of it shares
the same administrator credentials.

## Two things worth knowing before changing anything

**The timezone is part of the API contract.** Every timestamp column is `timestamp without
time zone`. The Node driver turned those into wire values by reading the stored wall-clock
number in the server process's local zone and emitting the corresponding UTC instant, so
`2025-01-15 10:23:45` went out as `2025-01-15T05:23:45.000Z` on an Almaty host and
something else elsewhere. `WIRE_TIMEZONE` pins it, and the value is logged at startup.
Changing it shifts every timestamp the API returns.

**The JSON is inconsistent, deliberately.** Most endpoints hand the database driver's
output straight to the client, which makes the driver's type mapping the contract:
`numeric` and `int8` arrive as strings, so `price` is `"1200.00"` and a bare `COUNT(*)` is
`"7"`, while an explicit `COUNT(*)::INT` stays a number. Casing follows the SQL alias, so
it varies per endpoint — `GET /api/auth/profile` is camelCase and `PUT /api/auth/profile`
is snake_case for the same entity. `PgJson` reproduces all of this in one place, and
`WireContractRulesTest` fails the build if someone adds a global naming strategy, a
null-suppressing annotation, or a fixed `@ResponseStatus`.

## Layout

```
wire/       PgJson (the driver emulator), ApiResponse, the error contract
security/   JWT for both identities, the three authenticators, RBAC, audit
repo/       All SQL. Company scoping lives in the method signatures
realtime/   Socket.IO server, rooms, and the after-commit side effects
support/    Notifications, chat messages, unread counters
<feature>/  One package per Express router, for easy diffing
```

Data access is plain JDBC rather than JPA. Every response is a SQL projection rather than
an entity — the same table is projected four different ways across four endpoints — and
company registration needs per-document savepoints, which `DataSourceTransactionManager`
supports and `JpaTransactionManager` does not.

## Differences from the Node backend

Everything below is a deliberate change. Everything else is reproduced as-is, including
the quirks.

### Defects fixed

| Area | What was wrong |
|---|---|
| `GET /api/consumer/catalog/access-requests` | Selected two columns that do not exist, so it returned 500 on every call |
| `GET /api/issues/consumer/:id` | Read the wrong request attribute, so it returned 500 on every call |
| `GET /api/users/:id` | Selected `u.*` and returned `password_hash` |
| `GET /api/users/audit-log` | Had no company filter, so any Owner or Manager could read every other company's audit entries |
| Order acceptance | Checked stock and decremented it separately, so concurrent accepts oversold; the `GREATEST(0, …)` clamp hid it |
| Order rejection | Wrote `products.updated_at`, a column that never existed, so every rejection of an accepted order threw |
| Resolved-issue notification | Addressed using a value read off the wrong object, so the buyer never received it |
| Empty mark-read | Broadcast a "zero unread" update that cleared everyone's badge |
| Socket.IO handshake | Unauthenticated; any client could join any room by naming it |
| JWT secrets | Fell back to a literal committed to this repository |
| Refresh tokens | Two sign-ins in the same second produced identical tokens, so revoking one revoked both |
| Registration races | Duplicate-key errors surfaced as 500 instead of the documented 409 |
| `last_active` | Written on every authenticated request; now throttled to once a minute |
| Transactions | There were none; checkout, registration and issue reporting could all half-complete |
| `/api/debug/*` | Unauthenticated DDL endpoints — not ported |

### Preserved on purpose

Decimals and some counts as JSON strings; per-endpoint casing; `register` returning
permission objects while `login` returns strings; company refresh tokens not rotating;
`GET /api/orders/pending` including accepted orders; notifications being read for a whole
company at once; unknown routes returning Express's HTML rather than JSON; `200` on
checkout and message-send despite creating rows.

## Tests

```bash
mvn verify     # needs Docker for Testcontainers
```

Four suites: the wire fidelity checks (string-vs-number, timezone rendering, key order),
the type mapping against a real Postgres, token and password compatibility with the Node
libraries, and the end-to-end HTTP contract. The ArchUnit rules run alongside them.

package com.supplierconsumer.consumerauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.supplierconsumer.repo.ConsumerRepository;
import com.supplierconsumer.repo.RefreshTokenRepository;
import com.supplierconsumer.security.JwtService;
import com.supplierconsumer.security.PasswordService;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.wire.ApiException;
import com.supplierconsumer.wire.ApiResponse;
import com.supplierconsumer.wire.PgJson;
import com.supplierconsumer.wire.WireError;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * {@code /api/consumer/auth} -- sign-in for buyers, who live in a different table from company
 * staff and carry a differently shaped token.
 *
 * <p>Two differences from the company side are real and preserved. Refresh <em>rotates</em> here:
 * the old token is revoked and a new one issued, where the company endpoint hands the same token
 * back. And the refresh response nests its payload under a {@code tokens} key, which the company
 * endpoint does not -- the company client stores {@code data} wholesale as its token object, so
 * the two shapes are not interchangeable.
 *
 * <p>There is also a six-character minimum password here that the company side does not enforce
 * at all.
 */
@RestController
@RequestMapping("/api/consumer/auth")
public class ConsumerAuthController {

    private final ConsumerRepository consumers;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordService passwords;
    private final JwtService jwt;
    private final PgJson pgJson;

    public ConsumerAuthController(ConsumerRepository consumers, RefreshTokenRepository refreshTokens,
                                  PasswordService passwords, JwtService jwt, PgJson pgJson) {
        this.consumers = consumers;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.jwt = jwt;
        this.pgJson = pgJson;
    }

    @PostMapping("/register")
    @WireError(message = "Internal server error during registration")
    @Transactional
    public ResponseEntity<ApiResponse> register(@RequestBody(required = false) RegisterRequest request) {
        RegisterRequest body = request == null
                ? new RegisterRequest(null, null, null, null, null) : request;

        if (isBlank(body.email()) || isBlank(body.password())
                || isBlank(body.firstName()) || isBlank(body.lastName())) {
            throw ApiException.badRequest("Email, password, first name, and last name are required");
        }
        if (body.password().length() < 6) {
            throw ApiException.badRequest("Password must be at least 6 characters long");
        }
        if (consumers.emailExists(body.email())) {
            throw ApiException.conflict("Consumer with this email already exists");
        }

        Map<String, Object> row;
        try {
            row = consumers.insert(body.email(), passwords.hash(body.password()),
                    body.firstName(), body.lastName(), body.phone());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Consumer with this email already exists");
        }

        long id = ((Number) row.get("id")).longValue();
        Tokens tokens = issueTokens(id, body.email());

        RegisteredConsumer consumer = new RegisteredConsumer(id, (String) row.get("email"),
                (String) row.get("first_name"), (String) row.get("last_name"),
                (String) row.get("phone"), (String) row.get("created_at"));

        return ResponseEntity.status(201).body(ApiResponse.ok()
                .message("Consumer registered successfully")
                .data(new RegisterPayload(consumer, tokens)));
    }

    @PostMapping("/login")
    @Transactional
    public ApiResponse login(@RequestBody(required = false) LoginRequest request) {
        LoginRequest body = request == null ? new LoginRequest(null, null) : request;

        if (isBlank(body.email()) || isBlank(body.password())) {
            throw ApiException.badRequest("Email and password are required");
        }

        var consumer = consumers.findForLogin(body.email())
                .orElseThrow(() -> ApiException.unauthorized("Invalid credentials"));

        if (!passwords.matches(body.password(), consumer.passwordHash())) {
            throw ApiException.unauthorized("Invalid credentials");
        }

        Tokens tokens = issueTokens(consumer.id(), consumer.email());

        // Read before the update, so the response shows the previous sign-in.
        String previousLogin = pgJson.jsDate(consumer.lastLogin());
        consumers.touchLastLogin(consumer.id());

        LoggedInConsumer payload = new LoggedInConsumer(consumer.id(), consumer.email(),
                consumer.firstName(), consumer.lastName(), consumer.phone(), consumer.isActive(),
                previousLogin, pgJson.jsDate(consumer.createdAt()));

        return ApiResponse.ok().message("Login successful").data(new LoginPayload(payload, tokens));
    }

    /** Rotates: the presented token is revoked and a fresh pair issued. No {@code message} key. */
    @PostMapping("/refresh-token")
    @Transactional
    public ApiResponse refresh(@RequestBody(required = false) RefreshRequest request) {
        String presented = request == null ? null : request.refreshToken();
        if (isBlank(presented)) {
            throw ApiException.badRequest("Refresh token is required");
        }

        String hash = jwt.hashRefreshToken(presented);
        var stored = refreshTokens.findValidConsumerToken(hash)
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));

        refreshTokens.revokeConsumerToken(hash);
        Tokens tokens = issueTokens(stored.consumerId(), stored.email());

        return ApiResponse.ok().data(new RefreshPayload(tokens));
    }

    /**
     * Unauthenticated, as in the original: it revokes whatever token it is handed and otherwise
     * succeeds, so a client with an expired access token can still sign out cleanly.
     */
    @PostMapping("/logout")
    @WireError(message = "Internal server error during logout")
    @Transactional
    public ApiResponse logout(@RequestBody(required = false) RefreshRequest request) {
        if (request != null && !isBlank(request.refreshToken())) {
            refreshTokens.revokeConsumerToken(jwt.hashRefreshToken(request.refreshToken()));
        }
        return ApiResponse.ok().message("Logout successful");
    }

    @GetMapping("/profile")
    public ApiResponse profile(Principals.Consumer principal) {
        Map<String, Object> row = consumers.findProfile(principal.id())
                .orElseThrow(() -> ApiException.notFound("Consumer not found"));

        LoggedInConsumer consumer = new LoggedInConsumer(
                ((Number) row.get("id")).longValue(),
                (String) row.get("email"),
                (String) row.get("first_name"),
                (String) row.get("last_name"),
                (String) row.get("phone"),
                (Boolean) row.get("is_active"),
                (String) row.get("last_login"),
                (String) row.get("created_at"));

        return ApiResponse.ok().data(new ProfilePayload(consumer));
    }

    private Tokens issueTokens(long consumerId, String email) {
        String accessToken = jwt.signConsumerAccess(consumerId, email);
        String refreshToken = jwt.signConsumerRefresh(consumerId, email);
        refreshTokens.storeForConsumer(consumerId, jwt.hashRefreshToken(refreshToken),
                LocalDateTime.now().plus(jwt.refreshTtl()));
        return new Tokens(accessToken, refreshToken);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonPropertyOrder({"accessToken", "refreshToken"})
    public record Tokens(
            @JsonProperty("accessToken") String accessToken,
            @JsonProperty("refreshToken") String refreshToken) {
    }

    @JsonPropertyOrder({"id", "email", "firstName", "lastName", "phone", "createdAt"})
    public record RegisteredConsumer(
            @JsonProperty("id") long id,
            @JsonProperty("email") String email,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("lastName") String lastName,
            @JsonProperty("phone") String phone,
            @JsonProperty("createdAt") String createdAt) {
    }

    @JsonPropertyOrder({"id", "email", "firstName", "lastName", "phone", "isActive", "lastLogin",
            "createdAt"})
    public record LoggedInConsumer(
            @JsonProperty("id") long id,
            @JsonProperty("email") String email,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("lastName") String lastName,
            @JsonProperty("phone") String phone,
            @JsonProperty("isActive") Boolean isActive,
            @JsonProperty("lastLogin") String lastLogin,
            @JsonProperty("createdAt") String createdAt) {
    }

    @JsonPropertyOrder({"consumer", "tokens"})
    public record RegisterPayload(
            @JsonProperty("consumer") RegisteredConsumer consumer,
            @JsonProperty("tokens") Tokens tokens) {
    }

    @JsonPropertyOrder({"consumer", "tokens"})
    public record LoginPayload(
            @JsonProperty("consumer") LoggedInConsumer consumer,
            @JsonProperty("tokens") Tokens tokens) {
    }

    public record ProfilePayload(@JsonProperty("consumer") LoggedInConsumer consumer) {
    }

    /** Nested under "tokens", unlike the company refresh response. */
    public record RefreshPayload(@JsonProperty("tokens") Tokens tokens) {
    }

    public record RegisterRequest(String email, String password, String firstName, String lastName,
                                  String phone) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }
}

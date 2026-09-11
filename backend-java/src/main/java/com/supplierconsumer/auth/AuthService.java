package com.supplierconsumer.auth;

import com.supplierconsumer.auth.dto.AuthDtos;
import com.supplierconsumer.repo.CompanyRepository;
import com.supplierconsumer.repo.RefreshTokenRepository;
import com.supplierconsumer.repo.UserRepository;
import com.supplierconsumer.security.AuditService;
import com.supplierconsumer.security.JwtService;
import com.supplierconsumer.security.PasswordService;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.wire.ApiException;
import com.supplierconsumer.wire.PgJson;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {

    private static final DateTimeFormatter ISO_INSTANT_MILLIS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneId.of("UTC"));

    private final UserRepository users;
    private final CompanyRepository companies;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordService passwords;
    private final JwtService jwt;
    private final AuditService audit;
    private final PgJson pgJson;

    public AuthService(UserRepository users, CompanyRepository companies,
                       RefreshTokenRepository refreshTokens, PasswordService passwords,
                       JwtService jwt, AuditService audit, PgJson pgJson) {
        this.users = users;
        this.companies = companies;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.jwt = jwt;
        this.audit = audit;
        this.pgJson = pgJson;
    }

    /**
     * Creates an account and signs it straight in.
     *
     * <p>Registering without a role makes you an Owner, and an Owner automatically gets a company
     * named after them. That is three statements -- insert user, insert company, point the user at
     * it -- which the original runs unprotected, so an interruption partway leaves an Owner with no
     * company, who then hits a confusing branch at login. Here they commit together or not at all.
     */
    @Transactional
    public AuthDtos.RegisterPayload register(AuthDtos.RegisterRequest request, HttpServletRequest http) {
        if (isBlank(request.email()) || isBlank(request.password())
                || isBlank(request.firstName()) || isBlank(request.lastName())) {
            throw ApiException.badRequest("Email, password, first name, and last name are required");
        }

        if (users.emailExists(request.email())) {
            throw ApiException.conflict("User with this email already exists");
        }

        String roleName = request.roleName() == null ? "Owner" : request.roleName();
        UserRepository.RoleRow role = users.findRoleByName(roleName)
                .orElseThrow(() -> ApiException.badRequest("Invalid role specified"));

        long userId;
        try {
            userId = users.insertUser(request.email(), passwords.hash(request.password()),
                    request.firstName(), request.lastName(), request.phone(), role.id(), null);
        } catch (DuplicateKeyException e) {
            // The existence check above is a read followed by a write, so two simultaneous
            // registrations can both pass it. Mapping the unique-violation back to the documented
            // 409 turns what would be a 500 into the response the contract already specifies.
            throw ApiException.conflict("User with this email already exists");
        }

        Long companyId = null;
        if ("Owner".equals(role.name())) {
            companyId = companies.insertCompany(
                    request.firstName() + " " + request.lastName() + " Company",
                    "Company owned by " + request.firstName() + " " + request.lastName(),
                    userId);
            users.setCompanyId(userId, companyId);
        }

        audit.log(null, "user_registered", "users", (int) userId,
                Map.of("email", request.email(), "roleName", roleName), http);

        AuthDtos.Tokens tokens = issueTokens(userId, request.email(), role.id(), role.name());
        users.touchLastLogin(userId);

        AuthDtos.RegisteredUser user = new AuthDtos.RegisteredUser(
                userId, request.email(), request.firstName(), request.lastName(), request.phone(),
                role.id(), role.name(), role.description(), companyId,
                users.permissionDetails(role.id()),
                // The original stamps this from the process clock rather than reading the row back.
                ISO_INSTANT_MILLIS.format(Instant.now().truncatedTo(ChronoUnit.MILLIS)));

        return new AuthDtos.RegisterPayload(user, tokens);
    }

    /**
     * Signs a user in, subject to the company approval gate.
     *
     * <p>The gate runs before the password is verified, which means an unapproved company's status
     * can be probed without valid credentials. That ordering is preserved because the frontend
     * relies on the 403 and its extra keys to drive the "waiting for approval" screen; closing the
     * side channel would be a behavioural change worth making deliberately, not as a side effect.
     */
    @Transactional
    public AuthDtos.LoginPayload login(AuthDtos.LoginRequest request, HttpServletRequest http) {
        if (isBlank(request.email()) || isBlank(request.password())) {
            throw ApiException.badRequest("Email and password are required");
        }

        UserRepository.LoginRow user = users.findForLogin(request.email())
                .orElseThrow(() -> ApiException.unauthorized("Invalid credentials"));

        if ("Owner".equals(user.roleName()) && user.companyId() != null) {
            if ("pending".equals(user.companyStatus())) {
                throw ApiException.forbidden("Wait until the admin approves you.")
                        .with("companyStatus", "pending");
            }
            if ("rejected".equals(user.companyStatus())) {
                String reason = user.rejectionMessage() == null ? "No reason provided" : user.rejectionMessage();
                throw ApiException.forbidden("Your company is kinda lame! " + reason)
                        .with("companyStatus", "rejected")
                        .with("rejectionMessage", reason);
            }
        }

        if (user.companyId() != null && Boolean.FALSE.equals(user.companyActive())) {
            throw ApiException.unauthorized("Account is deactivated. Please contact your administrator.");
        }

        if (!passwords.matches(request.password(), user.passwordHash())) {
            throw ApiException.unauthorized("Invalid credentials");
        }

        AuthDtos.Tokens tokens = issueTokens(user.id(), user.email(), user.roleId(), user.roleName());

        // Read before the update, so the response carries the previous sign-in, not this one.
        String previousLogin = pgJson.jsDate(user.lastLogin());
        users.touchLastLogin(user.id());
        audit.log(user.id(), "user_login", "auth", null, Map.of("email", request.email()), http);

        AuthDtos.LoggedInUser payload = new AuthDtos.LoggedInUser(
                user.id(), user.email(), user.firstName(), user.lastName(), user.phone(),
                user.roleId(), user.roleName(), user.roleDescription(), user.companyId(),
                users.permissionNames(user.roleId()), previousLogin);

        return new AuthDtos.LoginPayload(payload, tokens);
    }

    /**
     * Exchanges a refresh token for a new access token.
     *
     * <p>The same refresh token is handed back rather than rotated, matching the original. The
     * consumer side does rotate, so the two halves of the platform differ here; both are preserved
     * because clients store whatever they receive.
     */
    @Transactional
    public AuthDtos.RefreshPayload refresh(AuthDtos.RefreshRequest request, HttpServletRequest http) {
        if (isBlank(request.refreshToken())) {
            throw ApiException.badRequest("Refresh token is required");
        }

        String hash = jwt.hashRefreshToken(request.refreshToken());
        RefreshTokenRepository.StoredUserToken stored = refreshTokens.findValidUserToken(hash)
                .orElseThrow(() -> ApiException.unauthorized("Invalid or expired refresh token"));

        String accessToken = jwt.signCompanyAccess(
                stored.userId(), stored.email(), stored.roleId(), stored.roleName());

        audit.log(stored.userId(), "token_refreshed", "auth", null, null, http);

        return new AuthDtos.RefreshPayload(accessToken, request.refreshToken());
    }

    @Transactional
    public void logout(String refreshToken, Principals.Company user, HttpServletRequest http) {
        if (!isBlank(refreshToken)) {
            refreshTokens.revokeUserToken(jwt.hashRefreshToken(refreshToken));
        }
        if (user != null) {
            // Swallowed deliberately: a user whose row has already been deleted must still be
            // able to log out cleanly.
            audit.log(user.id(), "user_logout", "auth", null, null, http);
        }
    }

    @Transactional(readOnly = true)
    public AuthDtos.ProfilePayload profile(Principals.Company principal) {
        Map<String, Object> row = users.findProfile(principal.id())
                .orElseThrow(() -> ApiException.notFound("User not found"));

        int roleId = ((Number) row.get("role_id")).intValue();
        List<String> permissions = users.permissionNames(roleId);

        AuthDtos.ProfileUser user = new AuthDtos.ProfileUser(
                ((Number) row.get("id")).longValue(),
                (String) row.get("email"),
                (String) row.get("first_name"),
                (String) row.get("last_name"),
                (String) row.get("phone"),
                roleId,
                (String) row.get("role_name"),
                (String) row.get("role_description"),
                row.get("company_id") == null ? null : ((Number) row.get("company_id")).longValue(),
                permissions,
                (Boolean) row.get("is_active"),
                (String) row.get("last_login"),
                (String) row.get("created_at"));

        return new AuthDtos.ProfilePayload(user);
    }

    /**
     * Updates the signed-in user's own details.
     *
     * <p>Returns the raw updated row, so this response is snake_case while
     * {@code GET /api/auth/profile} is camelCase, for the same entity. The frontend spreads this
     * straight into its stored user object, so both spellings end up there.
     */
    @Transactional
    public Map<String, Object> updateProfile(Principals.Company principal,
                                             AuthDtos.UpdateProfileRequest request,
                                             HttpServletRequest http) {
        if (isBlank(request.firstName()) || isBlank(request.lastName())) {
            throw ApiException.badRequest("First name and last name are required");
        }

        Map<String, Object> updated = users.updateProfile(
                principal.id(), request.firstName(), request.lastName(), request.phone());

        audit.log(principal.id(), "profile_updated", "users", (int) principal.id(),
                mapOfNullable("firstName", request.firstName(), "lastName", request.lastName(),
                        "phone", request.phone()),
                http);

        return updated;
    }

    @Transactional
    public void changePassword(Principals.Company principal, AuthDtos.ChangePasswordRequest request,
                               HttpServletRequest http) {
        if (isBlank(request.currentPassword()) || isBlank(request.newPassword())) {
            throw ApiException.badRequest("Current password and new password are required");
        }

        String hash = users.findPasswordHash(principal.id())
                .orElseThrow(() -> ApiException.notFound("User not found"));

        if (!passwords.matches(request.currentPassword(), hash)) {
            throw ApiException.unauthorized("Current password is incorrect");
        }

        users.updatePassword(principal.id(), passwords.hash(request.newPassword()));
        audit.log(principal.id(), "password_changed", "users", (int) principal.id(), null, http);
    }

    private AuthDtos.Tokens issueTokens(long userId, String email, int roleId, String roleName) {
        String accessToken = jwt.signCompanyAccess(userId, email, roleId, roleName);
        String refreshToken = jwt.signCompanyRefresh(userId, email, roleId, roleName);
        refreshTokens.storeForUser(userId, jwt.hashRefreshToken(refreshToken),
                LocalDateTime.now().plus(jwt.refreshTtl()));
        return new AuthDtos.Tokens(accessToken, refreshToken);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Map.of rejects nulls, and phone is routinely null. */
    private static Map<String, Object> mapOfNullable(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}

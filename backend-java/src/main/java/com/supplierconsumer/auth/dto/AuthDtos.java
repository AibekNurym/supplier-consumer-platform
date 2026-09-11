package com.supplierconsumer.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * The hand-assembled responses of the auth endpoints.
 *
 * <p>These do not go through the row mapper because the Node handlers built them by hand, mapping
 * columns to camelCase -- except {@code company_id}, which stays snake_case inside an otherwise
 * camelCase object. Every component is annotated explicitly rather than relying on a naming
 * strategy, because no single strategy is correct here: {@code GET /api/auth/profile} returns
 * camelCase while {@code PUT /api/auth/profile} returns the raw snake_case row, for the same
 * entity.
 *
 * <p>The types are named after the endpoint rather than the entity, which is what makes that
 * possible.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    @JsonPropertyOrder({"accessToken", "refreshToken"})
    public record Tokens(
            @JsonProperty("accessToken") String accessToken,
            @JsonProperty("refreshToken") String refreshToken) {
    }

    /** A permission as {@code getUserPermissions} returns it: four columns, no id. */
    @JsonPropertyOrder({"name", "resource", "action", "description"})
    public record PermissionDetail(
            @JsonProperty("name") String name,
            @JsonProperty("resource") String resource,
            @JsonProperty("action") String action,
            @JsonProperty("description") String description) {
    }

    /**
     * The user object from {@code POST /api/auth/register}.
     *
     * <p>Note {@code permissions} holds full permission objects here, while the login and profile
     * responses hold plain name strings. That difference is real and has a visible consequence:
     * the frontend's {@code hasPermission} check compares against strings, so a freshly registered
     * user appears to have no permissions until their next login. It is preserved because
     * correcting it would change the response shape.
     */
    @JsonPropertyOrder({"id", "email", "firstName", "lastName", "phone", "roleId", "roleName",
            "roleDescription", "company_id", "permissions", "lastLogin"})
    public record RegisteredUser(
            @JsonProperty("id") long id,
            @JsonProperty("email") String email,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("lastName") String lastName,
            @JsonProperty("phone") String phone,
            @JsonProperty("roleId") int roleId,
            @JsonProperty("roleName") String roleName,
            @JsonProperty("roleDescription") String roleDescription,
            @JsonProperty("company_id") Long companyId,
            @JsonProperty("permissions") List<PermissionDetail> permissions,
            @JsonProperty("lastLogin") String lastLogin) {
    }

    /** The user object from {@code POST /api/auth/login}: permissions are name strings. */
    @JsonPropertyOrder({"id", "email", "firstName", "lastName", "phone", "roleId", "roleName",
            "roleDescription", "company_id", "permissions", "lastLogin"})
    public record LoggedInUser(
            @JsonProperty("id") long id,
            @JsonProperty("email") String email,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("lastName") String lastName,
            @JsonProperty("phone") String phone,
            @JsonProperty("roleId") int roleId,
            @JsonProperty("roleName") String roleName,
            @JsonProperty("roleDescription") String roleDescription,
            @JsonProperty("company_id") Long companyId,
            @JsonProperty("permissions") List<String> permissions,
            @JsonProperty("lastLogin") String lastLogin) {
    }

    /** The user object from {@code GET /api/auth/profile}: adds isActive and createdAt. */
    @JsonPropertyOrder({"id", "email", "firstName", "lastName", "phone", "roleId", "roleName",
            "roleDescription", "company_id", "permissions", "isActive", "lastLogin", "createdAt"})
    public record ProfileUser(
            @JsonProperty("id") long id,
            @JsonProperty("email") String email,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("lastName") String lastName,
            @JsonProperty("phone") String phone,
            @JsonProperty("roleId") int roleId,
            @JsonProperty("roleName") String roleName,
            @JsonProperty("roleDescription") String roleDescription,
            @JsonProperty("company_id") Long companyId,
            @JsonProperty("permissions") List<String> permissions,
            @JsonProperty("isActive") Boolean isActive,
            @JsonProperty("lastLogin") String lastLogin,
            @JsonProperty("createdAt") String createdAt) {
    }

    @JsonPropertyOrder({"user", "tokens"})
    public record RegisterPayload(
            @JsonProperty("user") RegisteredUser user,
            @JsonProperty("tokens") Tokens tokens) {
    }

    @JsonPropertyOrder({"user", "tokens"})
    public record LoginPayload(
            @JsonProperty("user") LoggedInUser user,
            @JsonProperty("tokens") Tokens tokens) {
    }

    @JsonPropertyOrder({"user"})
    public record ProfilePayload(@JsonProperty("user") ProfileUser user) {
    }

    /**
     * The refresh response payload.
     *
     * <p>Flat, not nested under a {@code tokens} key: the frontend's axios interceptor stores
     * {@code response.data.data} wholesale as its token object and then reads {@code .accessToken}
     * off it, so wrapping this a level deeper would break every silent re-authentication. The
     * consumer refresh endpoint does nest it, which is an inconsistency that has to be preserved
     * on both sides.
     */
    @JsonPropertyOrder({"accessToken", "refreshToken"})
    public record RefreshPayload(
            @JsonProperty("accessToken") String accessToken,
            @JsonProperty("refreshToken") String refreshToken) {
    }

    // -- request bodies -----------------------------------------------------------------
    // All fields nullable: express.json() leaves an unparsed body as {}, and the handler's own
    // validation produces the 400. Bean Validation would instead produce a different shape.

    public record RegisterRequest(String email, String password, String firstName,
                                  String lastName, String phone, String roleName) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record UpdateProfileRequest(String firstName, String lastName, String phone) {
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }
}

package com.supplierconsumer.security;

import com.supplierconsumer.wire.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Permission and role checks, with the denial messages and audit trail the originals produced.
 *
 * <p>A denial is not silent: the Node middleware writes an {@code unauthorized_access_attempt} row
 * before returning 403, which is what makes the audit log useful, so that is reproduced here.
 */
@Service
public class Authorization {

    private final AuditService audit;

    public Authorization(AuditService audit) {
        this.audit = audit;
    }

    /** The equivalent of {@code authorizePermission("products.create")}. */
    public void requirePermission(Principals.Company user, String permission, HttpServletRequest request) {
        if (user.hasPermission(permission)) {
            return;
        }
        audit.log(user.id(), "unauthorized_access_attempt", "permission", null,
                Map.of("requiredPermission", permission, "userRole", user.roleName()), request);
        throw ApiException.forbidden("Insufficient permissions. Required: " + permission);
    }

    /**
     * The equivalent of {@code authorizeRoles("Owner", "Manager")}. The message lists the roles
     * comma-separated, in the order the caller passed them.
     */
    public void requireRoles(Principals.Company user, HttpServletRequest request, String... roles) {
        if (user.hasAnyRole(roles)) {
            return;
        }
        audit.log(user.id(), "unauthorized_access_attempt", "role", null,
                Map.of("requiredRoles", String.join(", ", roles), "userRole", user.roleName()), request);
        throw ApiException.forbidden("Access denied. Required roles: " + String.join(", ", roles));
    }
}

package com.supplierconsumer.user;

import com.supplierconsumer.repo.CompanyRepository;
import com.supplierconsumer.repo.UserManagementRepository;
import com.supplierconsumer.repo.UserRepository;
import com.supplierconsumer.security.AuditService;
import com.supplierconsumer.security.Authorization;
import com.supplierconsumer.security.PasswordService;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.wire.ApiException;
import com.supplierconsumer.wire.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code /api/users} -- staff administration inside one company.
 *
 * <p>Two layers of authorisation apply. Route level: the list, role and audit endpoints need Owner
 * or Manager, and deactivating the company needs Owner. Target level: an Owner may manage anyone
 * in their company, a Manager only Sales Representatives -- which cannot be decided from the
 * caller's role alone, since it depends on who is being acted upon.
 *
 * <p>Everything is scoped to the caller's company, so another tenant's user id produces 404 rather
 * than 403 -- the endpoint does not reveal that the row exists.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final String SALES_REP = "Sales Representative";

    private final UserManagementRepository staff;
    private final UserRepository users;
    private final CompanyRepository companies;
    private final PasswordService passwords;
    private final Authorization authz;
    private final AuditService audit;

    public UserController(UserManagementRepository staff, UserRepository users,
                          CompanyRepository companies, PasswordService passwords,
                          Authorization authz, AuditService audit) {
        this.staff = staff;
        this.users = users;
        this.companies = companies;
        this.passwords = passwords;
        this.authz = authz;
        this.audit = audit;
    }

    @GetMapping
    public ApiResponse list(Principals.Company user, HttpServletRequest http) {
        authz.requireRoles(user, http, "Owner", "Manager");
        return ApiResponse.ok().data(staff.findAllForCompany(companyOf(user)));
    }

    /** Platform-wide, not company-scoped: the headcount spans every company. */
    @GetMapping("/roles")
    public ApiResponse roles(Principals.Company user, HttpServletRequest http) {
        authz.requireRoles(user, http, "Owner", "Manager");
        return ApiResponse.ok().data(staff.findAllRoles());
    }

    @GetMapping("/roles/{roleId}/permissions")
    public ApiResponse rolePermissions(@PathVariable String roleId, Principals.Company user,
                                       HttpServletRequest http) {
        authz.requireRoles(user, http, "Owner", "Manager");
        return ApiResponse.ok().data(staff.findRolePermissions(numericId(roleId)));
    }

    @GetMapping("/audit-log")
    public ApiResponse auditLog(@RequestParam(defaultValue = "1") String page,
                                @RequestParam(defaultValue = "50") String limit,
                                @RequestParam(required = false) String userId,
                                @RequestParam(required = false) String action,
                                @RequestParam(required = false) String resource,
                                Principals.Company user, HttpServletRequest http) {
        authz.requireRoles(user, http, "Owner", "Manager");

        int pageNumber = Math.max(1, parseOr(page, 1));
        int pageSize = Math.max(1, parseOr(limit, 50));
        int offset = (pageNumber - 1) * pageSize;

        UserManagementRepository.AuditPage result = staff.findAuditLog(companyOf(user),
                userId == null ? null : (long) parseOr(userId, 0), action, resource,
                pageSize, offset);

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", pageNumber);
        pagination.put("limit", pageSize);
        pagination.put("total", result.total());
        pagination.put("pages", (int) Math.ceil((double) result.total() / pageSize));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("logs", result.logs());
        data.put("pagination", pagination);

        return ApiResponse.ok().data(data);
    }

    /**
     * Deactivates the whole company. Declared before {@code /{id}} so the literal path wins, and
     * restricted to Owners. Nothing is deleted -- every row stays, only sign-in is withdrawn.
     */
    @DeleteMapping("/company")
    @Transactional
    public ApiResponse deactivateCompany(Principals.Company user, HttpServletRequest http) {
        authz.requireRoles(user, http, "Owner");

        if (!"Owner".equals(user.roleName())) {
            throw ApiException.forbidden("Only company owners can deactivate their company");
        }
        if (user.companyId() == null) {
            throw ApiException.badRequest("No company associated with this account");
        }

        boolean active = companies.isActive(user.companyId())
                .orElseThrow(() -> ApiException.notFound("Company not found"));
        if (!active) {
            throw ApiException.badRequest("Company is already deactivated");
        }

        // Written before the mutation, so the entry survives even though the actor is about to
        // lose the ability to sign in.
        audit.log(user.id(), "company_deactivated", "companies",
                user.companyId().intValue(), Map.of("companyId", user.companyId()), http);

        companies.deactivate(user.companyId());

        return ApiResponse.ok().message(
                "Company has been deactivated. All users can no longer log in, but data is preserved.");
    }

    @GetMapping("/{id}")
    public ApiResponse get(@PathVariable String id, Principals.Company user, HttpServletRequest http) {
        long companyId = companyOf(user);
        var target = staff.findExisting(numericId(id), companyId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        requireCanManage(user, target.roleName());

        Map<String, Object> row = new LinkedHashMap<>(staff.findForCompany(numericId(id), companyId)
                .orElseThrow(() -> ApiException.notFound("User not found")));
        row.put("permissions", users.permissionNames(target.roleId()));

        return ApiResponse.ok().data(row);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse> create(@RequestBody(required = false) CreateUserRequest request,
                                              Principals.Company user, HttpServletRequest http) {
        authz.requireRoles(user, http, "Owner", "Manager");
        CreateUserRequest body = request == null ? CreateUserRequest.empty() : request;

        if (isBlank(body.email()) || isBlank(body.password()) || isBlank(body.firstName())
                || isBlank(body.lastName()) || isBlank(body.roleName())) {
            throw ApiException.badRequest("All fields are required");
        }
        if (users.emailExists(body.email())) {
            throw ApiException.conflict("User with this email already exists");
        }

        var role = users.findRoleByName(body.roleName())
                .orElseThrow(() -> ApiException.badRequest("Invalid role specified"));

        // A Manager may only ever add Sales Representatives.
        if ("Manager".equals(user.roleName())) {
            if ("Owner".equals(role.name())) {
                throw ApiException.forbidden("Managers cannot create Owner accounts");
            }
            if ("Manager".equals(role.name())) {
                throw ApiException.forbidden("Managers cannot create other Manager accounts");
            }
        }

        Map<String, Object> created = staff.insert(body.email(), passwords.hash(body.password()),
                body.firstName(), body.lastName(), body.phone(), role.id(), companyOf(user), user.id());

        audit.log(user.id(), "user_created", "users", ((Number) created.get("id")).intValue(),
                Map.of("email", body.email(), "roleName", body.roleName(),
                        "createdFor", body.firstName() + " " + body.lastName()), http);

        return ResponseEntity.status(201).body(
                ApiResponse.ok().message("User created successfully").data(created));
    }

    /** A merge patch. A blank password means "leave it alone", not "set it to blank". */
    @PutMapping("/{id}")
    @Transactional
    public ApiResponse update(@PathVariable String id,
                              @RequestBody(required = false) UpdateUserRequest request,
                              Principals.Company user, HttpServletRequest http) {
        UpdateUserRequest body = request == null ? UpdateUserRequest.empty() : request;
        long targetId = numericId(id);

        var existing = staff.findExisting(targetId, companyOf(user))
                .orElseThrow(() -> ApiException.notFound("User not found"));
        requireCanManage(user, existing.roleName());

        int roleId = existing.roleId();
        if (body.roleName() != null && !body.roleName().equals(existing.roleName())) {
            if ("Manager".equals(user.roleName())) {
                if ("Owner".equals(body.roleName())) {
                    throw ApiException.forbidden("Managers cannot assign Owner role");
                }
                if ("Owner".equals(existing.roleName())) {
                    throw ApiException.forbidden("Managers cannot modify Owner accounts");
                }
                if ("Manager".equals(existing.roleName())) {
                    throw ApiException.forbidden("Managers cannot modify other Manager accounts");
                }
                if ("Manager".equals(body.roleName())) {
                    throw ApiException.forbidden("Managers cannot assign Manager role");
                }
            }
            roleId = users.findRoleByName(body.roleName())
                    .orElseThrow(() -> ApiException.badRequest("Invalid role specified"))
                    .id();
        }

        String passwordHash = existing.passwordHash();
        if (body.password() != null && !body.password().isBlank()) {
            passwordHash = passwords.hash(body.password());
        }

        Map<String, Object> updated = staff.update(targetId,
                body.firstName() != null && !body.firstName().isEmpty()
                        ? body.firstName() : existing.firstName(),
                body.lastName() != null && !body.lastName().isEmpty()
                        ? body.lastName() : existing.lastName(),
                // phone uses an explicit presence check rather than a truthiness test, so an
                // empty value genuinely clears it.
                body.phone() != null ? body.phone() : existing.phone(),
                roleId, passwordHash,
                body.isActive() != null ? body.isActive() : existing.isActive());

        audit.log(user.id(), "user_updated", "users", (int) targetId,
                Map.of("targetUser", existing.firstName() + " " + existing.lastName()), http);

        return ApiResponse.ok().message("User updated successfully").data(updated);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse delete(@PathVariable String id, Principals.Company user, HttpServletRequest http) {
        long targetId = numericId(id);

        var existing = staff.findExisting(targetId, companyOf(user))
                .orElseThrow(() -> ApiException.notFound("User not found"));
        requireCanManage(user, existing.roleName());

        if ("Manager".equals(user.roleName())) {
            if ("Owner".equals(existing.roleName())) {
                throw ApiException.forbidden("Managers cannot delete Owner accounts");
            }
            if ("Manager".equals(existing.roleName())) {
                throw ApiException.forbidden("Managers cannot delete other Manager accounts");
            }
        }
        if (targetId == user.id()) {
            throw ApiException.badRequest("You cannot delete your own account");
        }

        audit.log(user.id(), "user_deleted", "users", (int) targetId,
                Map.of("email", existing.email(),
                        "deletedUser", existing.firstName() + " " + existing.lastName()), http);

        // audit_log.user_id is ON DELETE SET NULL, so the history of what this user did survives.
        staff.delete(targetId);

        return ApiResponse.ok().message("User deleted successfully");
    }

    /**
     * The target-level rule: an Owner manages anyone, a Manager only Sales Representatives.
     * It needs the target's role, so it cannot be decided before the row is loaded.
     */
    private void requireCanManage(Principals.Company actor, String targetRole) {
        if ("Owner".equals(actor.roleName())) {
            return;
        }
        if ("Manager".equals(actor.roleName()) && SALES_REP.equals(targetRole)) {
            return;
        }
        throw ApiException.forbidden("Insufficient permissions to manage this user");
    }

    private long companyOf(Principals.Company user) {
        return user.requireCompanyId("User must be associated with a company");
    }

    private static int parseOr(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiException.notFound("User not found");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record CreateUserRequest(String email, String password, String firstName,
                                    String lastName, String phone, String roleName) {
        static CreateUserRequest empty() {
            return new CreateUserRequest(null, null, null, null, null, null);
        }
    }

    public record UpdateUserRequest(String firstName, String lastName, String phone,
                                    String roleName, Boolean isActive, String password) {
        static UpdateUserRequest empty() {
            return new UpdateUserRequest(null, null, null, null, null, null);
        }
    }
}

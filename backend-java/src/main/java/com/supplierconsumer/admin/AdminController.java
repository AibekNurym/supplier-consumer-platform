package com.supplierconsumer.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.supplierconsumer.repo.CompanyRepository;
import com.supplierconsumer.repo.DocumentRepository;
import com.supplierconsumer.repo.UserRepository;
import com.supplierconsumer.security.AdminOnly;
import com.supplierconsumer.security.PasswordService;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.wire.ApiException;
import com.supplierconsumer.wire.ApiResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /api/admin} -- the platform administrator's console, for approving company registrations.
 *
 * <p>Every handler takes an {@code @AdminOnly} principal, which is the equivalent of mounting the
 * whole router behind the {@code isAdmin} middleware. Administrator status comes from holding the
 * Admin role, not from a permission grant -- the Admin role deliberately has none.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CompanyRepository companies;
    private final DocumentRepository documents;
    private final UserRepository users;
    private final PasswordService passwords;

    public AdminController(CompanyRepository companies, DocumentRepository documents,
                           UserRepository users, PasswordService passwords) {
        this.companies = companies;
        this.documents = documents;
        this.users = users;
        this.passwords = passwords;
    }

    /**
     * Every company, not just the pending ones -- the handler is named for pending companies but
     * filters nothing, and the console groups them by status itself.
     */
    @GetMapping("/companies")
    public ApiResponse listCompanies(@AdminOnly Principals.Company admin) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> company : companies.findAllWithOwners()) {
            Map<String, Object> copy = new LinkedHashMap<>(company);
            copy.put("business_documents", enrichDocuments(company));
            rows.add(copy);
        }
        return ApiResponse.ok().data(rows);
    }

    /**
     * Adds a download link to each entry of the {@code business_documents} JSONB array.
     *
     * <p>Three shapes exist, because the storage strategy changed and old rows were never
     * migrated. A modern entry has an id and gets a link directly. A legacy entry has only a
     * filename, so the document is looked up by name within the company. If even that fails the
     * entry keeps its old on-disk path and is flagged {@code isOldFormat}, which the console
     * renders differently.
     *
     * <p>The link is the literal {@code /api/admin/documents/<id>}, including the {@code /api}
     * prefix. The console strips that prefix before calling an axios instance already based at
     * {@code /api}, so the prefix has to be there.
     */
    private Object enrichDocuments(Map<String, Object> company) {
        Object raw = company.get("business_documents");
        if (!(raw instanceof List<?> list)) {
            return raw;
        }

        long companyId = ((Number) company.get("id")).longValue();
        List<Object> enriched = new ArrayList<>(list.size());

        for (Object element : list) {
            if (!(element instanceof Map<?, ?> doc)) {
                enriched.add(element);
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) doc);

            if (copy.get("id") != null) {
                copy.put("downloadUrl", "/api/admin/documents/" + copy.get("id"));
                enriched.add(copy);
                continue;
            }

            Object originalName = copy.get("originalname");
            var resolved = originalName == null
                    ? java.util.Optional.<Long>empty()
                    : documents.findIdByOriginalName(companyId, originalName.toString());

            if (resolved.isPresent()) {
                copy.put("id", resolved.get());
                copy.put("downloadUrl", "/api/admin/documents/" + resolved.get());
            } else {
                copy.put("downloadUrl", copy.get("path"));
                copy.put("isOldFormat", true);
            }
            enriched.add(copy);
        }
        return enriched;
    }

    @PutMapping("/companies/{companyId}/approve")
    @Transactional
    public ApiResponse approve(@PathVariable String companyId, @AdminOnly Principals.Company admin) {
        long id = numericId(companyId);
        companies.findStatus(id).orElseThrow(() -> ApiException.notFound("Company not found"));

        return ApiResponse.ok()
                .message("Company approved successfully")
                .data(companies.approve(id));
    }

    @PutMapping("/companies/{companyId}/reject")
    @Transactional
    public ApiResponse reject(@PathVariable String companyId,
                              @RequestBody(required = false) RejectRequest request,
                              @AdminOnly Principals.Company admin) {
        String message = request == null ? null : request.rejectionMessage();
        if (message == null || message.isBlank()) {
            throw ApiException.badRequest("Rejection message is required");
        }

        long id = numericId(companyId);
        companies.findStatus(id).orElseThrow(() -> ApiException.notFound("Company not found"));

        return ApiResponse.ok()
                .message("Company rejected successfully")
                .data(companies.reject(id, message.trim()));
    }

    /**
     * A hard delete, and the only one in the API. Permitted solely for a rejected company, and it
     * takes the owner and everything downstream with it through the cascades.
     */
    @DeleteMapping("/companies/{companyId}")
    @Transactional
    public ApiResponse delete(@PathVariable String companyId, @AdminOnly Principals.Company admin) {
        long id = numericId(companyId);
        String status = companies.findStatus(id)
                .orElseThrow(() -> ApiException.notFound("Company not found"));

        if (!"rejected".equals(status)) {
            throw ApiException.badRequest("Only rejected companies can be deleted");
        }

        companies.delete(id);
        return ApiResponse.ok().message("Company and owner credentials deleted successfully");
    }

    @GetMapping("/profile")
    public ApiResponse profile(@AdminOnly Principals.Company admin) {
        return ApiResponse.ok().data(new AdminProfilePayload(new AdminProfile(
                admin.id(), admin.email(), admin.firstName(), admin.lastName(),
                adminPhone(admin.id()), admin.roleName(), admin.roleDescription())));
    }

    @PutMapping("/profile")
    @Transactional
    public ApiResponse updateProfile(@RequestBody(required = false) UpdateAdminProfileRequest request,
                                     @AdminOnly Principals.Company admin) {
        UpdateAdminProfileRequest body = request == null
                ? new UpdateAdminProfileRequest(null, null, null, null) : request;

        if (isBlank(body.firstName()) || isBlank(body.lastName())) {
            throw ApiException.badRequest("First name and last name are required");
        }
        if (body.email() != null && !body.email().equals(admin.email()) && users.emailExists(body.email())) {
            throw ApiException.conflict("Email already in use");
        }

        Map<String, Object> updated = users.updateAdminProfile(admin.id(),
                body.email() == null ? admin.email() : body.email(),
                body.firstName(), body.lastName(), body.phone());

        return ApiResponse.ok().message("Profile updated successfully").data(updated);
    }

    @PutMapping("/change-password")
    @Transactional
    public ApiResponse changePassword(@RequestBody(required = false) ChangePasswordRequest request,
                                      @AdminOnly Principals.Company admin) {
        ChangePasswordRequest body = request == null
                ? new ChangePasswordRequest(null, null) : request;

        if (isBlank(body.currentPassword()) || isBlank(body.newPassword())) {
            throw ApiException.badRequest("Current password and new password are required");
        }

        String hash = users.findPasswordHash(admin.id())
                .orElseThrow(() -> ApiException.notFound("Admin not found"));
        if (!passwords.matches(body.currentPassword(), hash)) {
            throw ApiException.unauthorized("Current password is incorrect");
        }

        users.updatePassword(admin.id(), passwords.hash(body.newPassword()));
        return ApiResponse.ok().message("Password changed successfully");
    }

    /** Streams the stored PDF. The only endpoint in the API that does not return JSON. */
    @GetMapping("/documents/{documentId}")
    public ResponseEntity<?> download(@PathVariable String documentId,
                                      @AdminOnly Principals.Company admin) {
        var document = documents.findForDownload(numericId(documentId));
        if (document.isEmpty()) {
            return ResponseEntity.status(404).body(ApiResponse.fail().message("Document not found"));
        }

        DocumentRepository.StoredDocument doc = document.get();
        String encodedName = URLEncoder.encode(doc.originalName(), StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedName + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(doc.size()))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(new ByteArrayResource(doc.data()));
    }

    @GetMapping("/documents/{documentId}/info")
    public ApiResponse documentInfo(@PathVariable String documentId,
                                    @AdminOnly Principals.Company admin) {
        Map<String, Object> info = documents.findInfo(numericId(documentId))
                .orElseThrow(() -> ApiException.notFound("Document not found"));
        return ApiResponse.ok().data(info);
    }

    private String adminPhone(long adminId) {
        return users.findProfile(adminId).map(row -> (String) row.get("phone")).orElse(null);
    }

    private long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiException.notFound("Company not found");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonPropertyOrder({"id", "email", "firstName", "lastName", "phone", "roleName", "roleDescription"})
    public record AdminProfile(
            @JsonProperty("id") long id,
            @JsonProperty("email") String email,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("lastName") String lastName,
            @JsonProperty("phone") String phone,
            @JsonProperty("roleName") String roleName,
            @JsonProperty("roleDescription") String roleDescription) {
    }

    public record AdminProfilePayload(@JsonProperty("user") AdminProfile user) {
    }

    public record RejectRequest(String rejectionMessage) {
    }

    public record UpdateAdminProfileRequest(String email, String firstName, String lastName,
                                            String phone) {
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }
}

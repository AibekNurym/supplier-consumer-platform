package com.supplierconsumer.companyregistration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplierconsumer.repo.CompanyRepository;
import com.supplierconsumer.repo.DocumentRepository;
import com.supplierconsumer.repo.UserRepository;
import com.supplierconsumer.security.PasswordService;
import com.supplierconsumer.wire.ApiException;
import com.supplierconsumer.wire.ApiResponse;
import com.supplierconsumer.wire.WireError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /api/company/register} -- public self-service registration for a new supplier.
 *
 * <p>Deliberately unauthenticated: this is how a company first appears on the platform. It
 * arrives as multipart, with the applicant's details and up to ten supporting documents under the
 * field name {@code documents}.
 *
 * <p>The company is created with status {@code pending} and stays unusable until an administrator
 * approves it -- its Owner is blocked at login until then.
 */
@RestController
@RequestMapping("/api/company")
public class CompanyRegistrationController {

    private static final Logger log = LoggerFactory.getLogger(CompanyRegistrationController.class);

    private final UserRepository users;
    private final CompanyRepository companies;
    private final DocumentRepository documents;
    private final DocumentWriter documentWriter;
    private final PasswordService passwords;
    private final ObjectMapper mapper;

    public CompanyRegistrationController(UserRepository users, CompanyRepository companies,
                                         DocumentRepository documents, DocumentWriter documentWriter,
                                         PasswordService passwords, ObjectMapper mapper) {
        this.users = users;
        this.companies = companies;
        this.documents = documents;
        this.documentWriter = documentWriter;
        this.passwords = passwords;
        this.mapper = mapper;
    }

    @PostMapping(path = "/register", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE,
            MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.ALL_VALUE})
    @WireError(message = "Internal server error during company registration")
    @Transactional
    public ResponseEntity<ApiResponse> register(
            @RequestParam(required = false) String companyName,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) String lastName,
            @RequestParam(required = false) String phone,
            @RequestParam(name = "documents", required = false) List<MultipartFile> uploads) {

        if (isBlank(companyName) || isBlank(email) || isBlank(password)
                || isBlank(firstName) || isBlank(lastName)) {
            throw ApiException.badRequest(
                    "Company name, email, password, first name, and last name are required");
        }
        if (users.emailExists(email)) {
            throw ApiException.conflict("User with this email already exists");
        }
        if (companies.nameExists(companyName)) {
            throw ApiException.conflict("Company with this name already exists");
        }

        var ownerRole = users.findRoleByName("Owner")
                .orElseThrow(() -> new ApiException(500, "Owner role not found in database"));

        long userId;
        long companyId;
        try {
            userId = users.insertUser(email, passwords.hash(password), firstName, lastName,
                    phone, ownerRole.id(), null);
            companyId = companies.insertCompany(companyName,
                    "Company registered by " + firstName + " " + lastName, userId);
        } catch (DuplicateKeyException e) {
            // The two existence checks above are reads followed by writes, so simultaneous
            // registrations can both pass them. Mapping the unique violation back to the
            // documented 409 turns a race that produced a 500 into the contract's own response.
            throw conflictFor(e);
        }

        // Each document gets its own savepoint, so one bad file is skipped rather than taking the
        // registration down with it -- see DocumentWriter.
        List<Long> storedIds = new ArrayList<>();
        if (uploads != null) {
            for (MultipartFile file : uploads) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                try {
                    storedIds.add(documentWriter.store(companyId, file));
                } catch (Exception e) {
                    log.error("Error storing file: {}", file.getOriginalFilename(), e);
                }
            }
        }

        // Document metadata is duplicated into companies.business_documents so the admin console
        // can list attachments without touching the blobs.
        if (!storedIds.isEmpty()) {
            List<Map<String, Object>> metadata = documents.findMetadataForCompany(companyId);
            try {
                companies.setBusinessDocuments(companyId, mapper.writeValueAsString(metadata));
            } catch (Exception e) {
                log.error("Failed to record document metadata for company {}", companyId, e);
            }
        }

        users.setCompanyId(userId, companyId);

        RegistrationPayload payload = new RegistrationPayload(
                new RegisteredCompany(companyId, companyName, "pending"),
                new RegisteredOwner(userId, email, firstName, lastName));

        return ResponseEntity.status(201).body(ApiResponse.ok()
                .message("Company registration submitted successfully. Please wait for admin approval.")
                .data(payload));
    }

    private ApiException conflictFor(DuplicateKeyException e) {
        String detail = e.getMostSpecificCause().getMessage();
        if (detail != null && detail.contains("companies_name")) {
            return ApiException.conflict("Company with this name already exists");
        }
        return ApiException.conflict("User with this email already exists");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonPropertyOrder({"id", "name", "status"})
    public record RegisteredCompany(
            @JsonProperty("id") long id,
            @JsonProperty("name") String name,
            @JsonProperty("status") String status) {
    }

    @JsonPropertyOrder({"id", "email", "firstName", "lastName"})
    public record RegisteredOwner(
            @JsonProperty("id") long id,
            @JsonProperty("email") String email,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("lastName") String lastName) {
    }

    @JsonPropertyOrder({"company", "user"})
    public record RegistrationPayload(
            @JsonProperty("company") RegisteredCompany company,
            @JsonProperty("user") RegisteredOwner user) {
    }
}

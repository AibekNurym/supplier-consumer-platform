package com.supplierconsumer.issue;

import com.supplierconsumer.repo.IssueRepository;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.wire.ApiException;
import com.supplierconsumer.wire.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code /api/issues} -- problems a buyer raises against an order.
 *
 * <p>Served to both identities, split by path: the buyer reports and tracks, the supplier triages
 * and resolves.
 */
@RestController
@RequestMapping("/api/issues")
public class IssueController {

    private final IssueRepository issues;
    private final IssueService service;

    public IssueController(IssueRepository issues, IssueService service) {
        this.issues = issues;
        this.service = service;
    }

    /** Adds a top-level {@code chatMessage} alongside {@code data}, outside the usual envelope. */
    @PostMapping
    public ResponseEntity<ApiResponse> report(@RequestBody(required = false) ReportRequest request,
                                              Principals.Consumer consumer) {
        ReportRequest body = request == null ? new ReportRequest(null, null, null) : request;

        IssueService.Reported reported = service.report(consumer, body.orderId(), body.title(),
                body.description());

        return ResponseEntity.status(201).body(ApiResponse.ok()
                .message("Issue reported successfully")
                .data(reported.issue())
                .put("chatMessage", reported.chatMessage()));
    }

    @GetMapping("/consumer")
    public ApiResponse consumerIssues(Principals.Consumer consumer) {
        return ApiResponse.ok().data(issues.findForConsumer(consumer.id()));
    }

    @GetMapping("/consumer/{issueId}")
    public ApiResponse consumerIssueDetail(@PathVariable String issueId, Principals.Consumer consumer) {
        Map<String, Object> issue = issues.findDetailForConsumer(numericId(issueId), consumer.id())
                .orElseThrow(() -> ApiException.notFound("Issue not found"));
        return ApiResponse.ok().data(issue);
    }

    @GetMapping("/company")
    public ApiResponse companyIssues(Principals.Company user) {
        return ApiResponse.ok().data(issues.findForCompany(companyOf(user)));
    }

    @GetMapping("/company/{issueId}")
    public ApiResponse companyIssueDetail(@PathVariable String issueId, Principals.Company user) {
        Map<String, Object> issue = issues.findDetailForCompany(numericId(issueId), companyOf(user))
                .orElseThrow(() -> ApiException.notFound("Issue not found"));
        return ApiResponse.ok().data(issue);
    }

    /** Owners appear here too, despite the name -- both roles are assignable. */
    @GetMapping("/managers")
    public ApiResponse managers(Principals.Company user) {
        return ApiResponse.ok().data(issues.findAssignableStaff(companyOf(user)));
    }

    @PutMapping("/{issueId}/assign")
    public ApiResponse assign(@PathVariable String issueId,
                              @RequestBody(required = false) AssignRequest request,
                              Principals.Company user) {
        Long managerId = request == null ? null : request.managerId();
        Map<String, Object> updated = service.assign(numericId(issueId), managerId, companyOf(user));
        return ApiResponse.ok().message("Issue assigned to manager").data(updated);
    }

    @PutMapping("/{issueId}/resolve")
    public ApiResponse resolve(@PathVariable String issueId,
                               @RequestBody(required = false) ResolveRequest request,
                               Principals.Company user) {
        String notes = request == null ? null : request.resolutionNotes();
        Map<String, Object> updated = service.resolve(numericId(issueId), notes, user, companyOf(user));
        return ApiResponse.ok().message("Issue resolved successfully").data(updated);
    }

    private long companyOf(Principals.Company user) {
        return user.requireCompanyId("User must be associated with a company");
    }

    private long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Invalid order ID");
        }
    }

    public record ReportRequest(Long orderId, String title, String description) {
    }

    public record AssignRequest(Long managerId) {
    }

    public record ResolveRequest(String resolutionNotes) {
    }
}

package com.supplierconsumer.issue;

import com.supplierconsumer.repo.ConsumerAccessRepository;
import com.supplierconsumer.repo.IssueRepository;
import com.supplierconsumer.repo.UserRepository;
import com.supplierconsumer.security.Principals;
import com.supplierconsumer.support.ChatMessageService;
import com.supplierconsumer.wire.ApiException;
import com.supplierconsumer.wire.JsNumbers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class IssueService {

    private final IssueRepository issues;
    private final ConsumerAccessRepository access;
    private final UserRepository users;
    private final ChatMessageService chat;
    private final ApplicationEventPublisher events;

    public IssueService(IssueRepository issues, ConsumerAccessRepository access, UserRepository users,
                        ChatMessageService chat, ApplicationEventPublisher events) {
        this.issues = issues;
        this.access = access;
        this.users = users;
        this.chat = chat;
        this.events = events;
    }

    /**
     * Raises an issue and posts a summary of it into the buyer/supplier conversation.
     *
     * <p>Both must happen or neither: the summary is how the supplier actually learns about the
     * issue, and it is returned in the response as {@code chatMessage}. The original enforces this
     * by deleting the issue row by hand when the chat insert fails, which is a worse rollback than
     * a real one -- if the compensating delete itself fails, the issue is orphaned. Here the
     * transaction rolls both back.
     *
     * <p>The failure message is kept exactly as it was, even though it now describes something
     * that did not happen: the frontend uses these strings as translation keys, so fidelity beats
     * accuracy.
     */
    @Transactional
    public Reported report(Principals.Consumer consumer, Long orderId, String title,
                           String description) {
        if (orderId == null || title == null || title.isBlank()
                || description == null || description.isBlank()) {
            throw ApiException.badRequest("Order ID, title, and description are required");
        }

        String trimmedTitle = title.trim();
        String trimmedDescription = description.trim();

        IssueRepository.OrderForIssue order = issues.findOrderForConsumer(orderId, consumer.id())
                .orElseThrow(() -> ApiException.notFound("Order not found"));

        // Reporting a problem grants access on its own, so a buyer can always discuss an issue
        // even if their access was never approved or has since been withdrawn.
        access.grantAccess(consumer.id(), order.companyId(), null);

        Map<String, Object> issue = issues.insert(orderId, consumer.id(), order.companyId(),
                trimmedTitle, trimmedDescription);

        Map<String, Object> chatMessage;
        try {
            chatMessage = chat.insert(consumer.id(), order.companyId(), "consumer", consumer.id(),
                    buildSummary(orderId, ((Number) issue.get("id")).longValue(), consumer,
                            trimmedTitle, trimmedDescription),
                    "issue_summary", null, null, null, null);
        } catch (RuntimeException e) {
            throw new ApiException(500, "Issue created but failed to add summary to chat");
        }
        if (chatMessage == null) {
            throw new ApiException(500, "Issue created but failed to add summary to chat");
        }

        events.publishEvent(new IssueReported(issue, chatMessage, consumer.id(), order.companyId(),
                orderId, displayName(consumer)));

        return new Reported(issue, chatMessage);
    }

    @Transactional
    public Map<String, Object> assign(long issueId, Long assigneeId, long companyId) {
        IssueRepository.IssueRow issue = issues.findIssue(issueId)
                .orElseThrow(() -> ApiException.notFound("Issue not found"));
        if (issue.companyId() != companyId) {
            throw ApiException.notFound("Issue not found");
        }
        if (assigneeId == null) {
            throw ApiException.badRequest("Invalid manager selected");
        }

        // Owners are assignable too, despite the endpoint's name.
        String role = issues.findAssignableRole(assigneeId, companyId)
                .orElseThrow(() -> ApiException.badRequest("Invalid manager selected"));

        Map<String, Object> updated = issues.assign(issueId, assigneeId, role);
        events.publishEvent(new IssueUpdated(updated, issue.consumerId(), companyId));
        return updated;
    }

    /** Any employee of the company may resolve any of its issues, assigned or not. */
    @Transactional
    public Map<String, Object> resolve(long issueId, String resolutionNotes,
                                       Principals.Company user, long companyId) {
        IssueRepository.IssueRow issue = issues.findIssue(issueId)
                .orElseThrow(() -> ApiException.notFound("Issue not found"));
        if (issue.companyId() != companyId) {
            throw ApiException.notFound("Issue not found");
        }

        String notes = resolutionNotes == null || resolutionNotes.isBlank() ? null : resolutionNotes;
        Map<String, Object> updated = issues.resolve(issueId, user.id(), notes);

        String resolver = user.firstName() + " " + user.lastName();
        String message = notes != null
                ? "✅ Issue Resolved by " + resolver + ":\n\n\"" + issue.title()
                        + "\"\n\nResolution: " + notes
                : "✅ Issue Resolved by " + resolver + ":\n\n\"" + issue.title()
                        + "\"\n\nThis issue has been resolved.";

        // Unlike the report path, a failure here is swallowed: the issue is resolved either way,
        // and the chat note is a courtesy.
        Map<String, Object> chatMessage = null;
        try {
            chatMessage = chat.insert(issue.consumerId(), companyId, "company", user.id(),
                    message, "issue_resolution", null, null, null, null);
        } catch (RuntimeException ignored) {
            // Deliberately ignored, matching the original.
        }

        events.publishEvent(new IssueResolved(updated, chatMessage, issue.consumerId(), companyId,
                issue.orderId()));

        return updated;
    }

    private String buildSummary(long orderId, long issueId, Principals.Consumer consumer,
                                String title, String description) {
        IssueRepository.OrderContext context = issues.findOrderContext(orderId).orElse(null);
        List<IssueRepository.ItemLine> items = issues.findItemLines(orderId);

        String itemsList = items.isEmpty()
                ? "- No order items found"
                : items.stream()
                        .map(item -> "- " + item.productName() + " x" + item.quantity()
                                + " = ₸" + JsNumbers.toFixed2(JsNumbers.parseFloat(item.totalPrice())))
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");

        StringBuilder message = new StringBuilder();
        message.append("⚠️ Order Issue Reported\n\n");
        message.append("Order #: ").append(orderId).append('\n');
        message.append("Issue #: ").append(issueId).append('\n');
        message.append("Consumer: ").append(displayName(consumer)).append('\n');
        if (consumer.email() != null) {
            message.append("Email: ").append(consumer.email()).append('\n');
        }
        if (consumer.phone() != null && !consumer.phone().isBlank()) {
            message.append("Phone: ").append(consumer.phone()).append('\n');
        }
        message.append("Title: ").append(title).append('\n');
        message.append("Description: ").append(description).append('\n');
        if (context != null) {
            if (context.totalAmount() != null) {
                message.append("Order Total: ₸")
                        .append(JsNumbers.toFixed2(JsNumbers.parseFloat(context.totalAmount())))
                        .append('\n');
            }
            if (context.paymentMethod() != null) {
                message.append("Payment: ").append(context.paymentMethod()).append('\n');
            }
            if (context.deliveryMethod() != null) {
                message.append("Delivery: ").append(context.deliveryMethod()).append('\n');
            }
            if (context.deliveryAddress() != null && !context.deliveryAddress().isBlank()) {
                message.append("Address: ").append(context.deliveryAddress()).append('\n');
            }
        }
        message.append("\nItems:\n").append(itemsList);
        return message.toString();
    }

    private String displayName(Principals.Consumer consumer) {
        String name = ((consumer.firstName() == null ? "" : consumer.firstName()) + " "
                + (consumer.lastName() == null ? "" : consumer.lastName())).trim();
        return name.isEmpty() ? "Consumer" : name;
    }

    public record Reported(Map<String, Object> issue, Map<String, Object> chatMessage) {
    }

    public record IssueReported(Map<String, Object> issue, Map<String, Object> chatMessage,
                                long consumerId, long companyId, long orderId, String consumerName) {
    }

    public record IssueUpdated(Map<String, Object> issue, long consumerId, long companyId) {
    }

    public record IssueResolved(Map<String, Object> issue, Map<String, Object> chatMessage,
                                long consumerId, long companyId, long orderId) {
    }
}

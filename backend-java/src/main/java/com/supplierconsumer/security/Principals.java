package com.supplierconsumer.security;

import java.util.List;
import java.util.Set;

/**
 * The authenticated identities. There are two of them because the platform keeps company staff
 * and buyers in separate tables with separate token shapes, and a third view used only by chat,
 * which is the one place a handler serves both.
 */
public final class Principals {

    private Principals() {
    }

    /**
     * A member of company staff, or the platform administrator.
     *
     * <p>{@code companyId} is null for the administrator, who belongs to no company. Permissions
     * come from the role, and {@code roleName} is compared against literal strings in several
     * places ({@code "Owner"}, {@code "Manager"}, {@code "Sales Representative"},
     * {@code "Admin"}), so the seeded names are load-bearing.
     */
    public record Company(
            long id,
            String email,
            String firstName,
            String lastName,
            int roleId,
            String roleName,
            String roleDescription,
            Long companyId,
            Set<String> permissions) {

        public boolean isAdmin() {
            return "Admin".equals(roleName);
        }

        public boolean hasPermission(String permission) {
            return permissions.contains(permission);
        }

        public boolean hasAnyRole(String... roles) {
            return List.of(roles).contains(roleName);
        }

        /**
         * Most queries are scoped by company, and a staff account with no company must not be
         * able to read another company's rows by omitting the filter.
         */
        public long requireCompanyId(String message) {
            if (companyId == null) {
                throw com.supplierconsumer.wire.ApiException.forbidden(message);
            }
            return companyId;
        }
    }

    /** A buyer. Carries no role and no company: authorisation is per access grant instead. */
    public record Consumer(
            long id,
            String email,
            String firstName,
            String lastName,
            String phone) {
    }

    /**
     * Either identity, as the chat routes see it.
     *
     * <p>Chat is the only area whose middleware accepts both token families and normalises them,
     * and {@code type} is the discriminator its handlers branch on. It exists nowhere else in the
     * codebase.
     */
    public record Chat(
            String type,
            long id,
            String email,
            String firstName,
            String lastName,
            Long companyId,
            String roleName) {

        public boolean isConsumer() {
            return "consumer".equals(type);
        }

        public boolean isCompany() {
            return "company".equals(type);
        }
    }
}

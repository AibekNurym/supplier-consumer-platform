package com.supplierconsumer.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Structural guards for the parts of the contract that are easy to "tidy up" into a break.
 *
 * <p>Each rule here exists because the tidier-looking alternative would silently change what
 * clients receive. They are cheap to run and they fail loudly, which is the point: a code review
 * will not reliably catch a stray {@code @ResponseStatus}.
 */
class WireContractRulesTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.supplierconsumer");
    }

    @Test
    @DisplayName("no handler declares a fixed status, because several vary per branch")
    void noResponseStatusAnnotations() {
        // request-access answers 201 when it creates a row and 200 when it reopens one; checkout
        // and send-message create rows but answer 200. A fixed status would flatten all of that.
        ArchRule rule = noMethods()
                .should().beAnnotatedWith("org.springframework.web.bind.annotation.ResponseStatus")
                .because("status codes here depend on which branch ran, not on the HTTP method");

        rule.check(classes);
    }

    @Test
    @DisplayName("no global property naming strategy is configured")
    void noJsonNamingAnnotations() {
        // Casing is per endpoint: GET /api/auth/profile is camelCase and PUT /api/auth/profile is
        // snake_case, for the same entity. Any blanket strategy breaks one of them.
        ArchRule rule = noClasses()
                .should().beAnnotatedWith("com.fasterxml.jackson.databind.annotation.JsonNaming")
                .because("casing varies per endpoint; each DTO names its fields explicitly");

        rule.check(classes);
    }

    @Test
    @DisplayName("no DTO suppresses null fields")
    void noJsonIncludeNonNull() {
        // JSON.stringify drops undefined, not null, and clients test for keys like phone and
        // readAt. Omission is handled deliberately in ApiResponse, not by an inclusion rule.
        ArchRule rule = noClasses()
                .should().beAnnotatedWith("com.fasterxml.jackson.annotation.JsonInclude")
                .because("real nulls must stay on the wire; only ApiResponse omits keys");

        rule.check(classes);
    }

    @Test
    @DisplayName("repositories are the only place that talks to the database")
    void onlyRepositoriesUseJdbc() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages("com.supplierconsumer.repo",
                        "com.supplierconsumer.security", "com.supplierconsumer.config")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
                .because("company scoping lives in repository signatures and must not be bypassed");

        rule.check(classes);
    }
}

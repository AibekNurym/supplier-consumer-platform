package com.supplierconsumer.config;

import com.supplierconsumer.repo.UserRepository;
import com.supplierconsumer.security.PasswordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Ensures a platform administrator exists, since company approvals are impossible without one.
 *
 * <p>The Node version creates {@code admin@platform.com} with a password written into the source,
 * which means every deployment of it shares the same administrator credentials. Here the password
 * comes from configuration; when none is set, a random one is generated and logged once, so a
 * fresh install is usable without a known-in-advance password existing anywhere.
 *
 * <p>Idempotent, and it never touches an existing account -- an upgrade over a live database
 * leaves the current administrator exactly as it is.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final JdbcClient db;
    private final UserRepository users;
    private final PasswordService passwords;
    private final String adminEmail;
    private final String configuredPassword;

    public AdminBootstrap(JdbcClient db, UserRepository users, PasswordService passwords,
                          @Value("${app.bootstrap.admin.email:admin@platform.com}") String adminEmail,
                          @Value("${app.bootstrap.admin.password:}") String configuredPassword) {
        this.db = db;
        this.users = users;
        this.passwords = passwords;
        this.adminEmail = adminEmail;
        this.configuredPassword = configuredPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.emailExists(adminEmail)) {
            log.debug("Administrator {} already present", adminEmail);
            return;
        }

        var adminRole = users.findRoleByName("Admin");
        if (adminRole.isEmpty()) {
            log.warn("Admin role missing; skipping administrator bootstrap");
            return;
        }

        boolean generated = configuredPassword == null || configuredPassword.isBlank();
        String password = generated ? randomPassword() : configuredPassword;

        // company_id stays null: the administrator belongs to the platform, not to a company,
        // which is also what lets them past the company-status gate at login.
        long id = users.insertUser(adminEmail, passwords.hash(password), "Platform", "Admin",
                null, adminRole.get().id(), null);

        if (generated) {
            log.warn("""

                            ================================================================
                            Created platform administrator {} with a generated password:

                                {}

                            This is shown once. Set app.bootstrap.admin.password (or
                            ADMIN_PASSWORD) to choose it yourself, and change it after
                            signing in.
                            ================================================================""",
                    adminEmail, password);
        } else {
            log.info("Created platform administrator {} (id {}) from configuration", adminEmail, id);
        }
    }

    private String randomPassword() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

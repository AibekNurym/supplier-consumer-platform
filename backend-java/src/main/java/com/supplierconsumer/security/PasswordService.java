package com.supplierconsumer.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password hashing, at the same cost factor the Node code used.
 *
 * <p>Strength 12 is not a preference here, it is a compatibility requirement: bcryptjs produced
 * {@code $2b$12$...} digests and every stored password is one of those. Spring's encoder reads
 * that format directly, so existing accounts keep working untouched.
 */
@Service
public class PasswordService {

    private static final int COST = 12;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(COST);

    public String hash(String raw) {
        return encoder.encode(raw);
    }

    public boolean matches(String raw, String hash) {
        if (raw == null || hash == null) {
            return false;
        }
        return encoder.matches(raw, hash);
    }
}

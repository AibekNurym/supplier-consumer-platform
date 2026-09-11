package com.supplierconsumer.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Principals.Company} parameter as requiring the platform administrator, the
 * equivalent of mounting a router behind the {@code isAdmin} middleware.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AdminOnly {
}

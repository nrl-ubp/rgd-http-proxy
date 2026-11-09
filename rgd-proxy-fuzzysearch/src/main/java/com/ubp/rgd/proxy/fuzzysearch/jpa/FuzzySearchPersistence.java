package com.ubp.rgd.proxy.fuzzysearch.jpa;

import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Qualifies the {@code EntityManager} and {@code EntityManagerFactory} of the fuzzy search, bound to
 * the {@code fuzzy-search} datasource, so that they can never be mistaken for those of another module.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE})
public @interface FuzzySearchPersistence {
}

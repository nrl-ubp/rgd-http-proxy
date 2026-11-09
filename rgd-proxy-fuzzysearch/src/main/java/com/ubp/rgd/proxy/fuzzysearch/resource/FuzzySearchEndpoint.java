package com.ubp.rgd.proxy.fuzzysearch.resource;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the fuzzy search resources, so that {@link FuzzySearchEnabledFilter} applies to them only.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface FuzzySearchEndpoint {
}

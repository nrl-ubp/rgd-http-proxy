package com.ubp.rgd.proxy.transform;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Names a concrete {@link EndPointTransformer} implementation.
 * <p>
 * Every implementation is a bean of the same interface, so without a qualifier an injection point of
 * type {@code EndPointTransformer} would be ambiguous. Carrying this qualifier also removes the
 * {@code @Default} qualifier from the implementations, so they do not compete with the bean produced
 * by {@link EndPointTransformerProducer}: application code injects the produced one and gets
 * whichever implementation the configuration selected.
 *
 * @see EndPointTransformerProducer
 */
@Qualifier
@Retention(RUNTIME)
@Target({TYPE, FIELD, METHOD, PARAMETER})
public @interface TransformerImpl {

    /** Tokenization through the RegData RPS engine. */
    String RPS = "RPS";

    /** Encryption through Format Preserving Encryption. */
    String FPE = "FPE";

    /**
     * The name of the implementation, as accepted by {@code proxy.transform.impl}.
     *
     * @return {@link #RPS} or {@link #FPE}
     */
    String value();

    /**
     * Literal allowing an implementation to be looked up by name at runtime.
     */
    class Literal extends AnnotationLiteral<TransformerImpl> implements TransformerImpl {

        private final String value;

        public Literal(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }
    }
}

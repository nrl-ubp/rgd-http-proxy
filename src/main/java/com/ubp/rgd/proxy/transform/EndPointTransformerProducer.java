package com.ubp.rgd.proxy.transform;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the {@link EndPointTransformer} the proxy runs with, chosen by {@code proxy.transform.impl}.
 * <p>
 * Every implementation carries a {@link TransformerImpl} qualifier, so none of them is the default
 * bean and application code injecting a plain {@code EndPointTransformer} gets the one produced
 * here.
 *
 * <pre>
 * proxy.transform.impl=RPS   # tokenization through the RegData RPS engine
 * proxy.transform.impl=FPE   # Format Preserving Encryption, no engine needed
 * </pre>
 *
 * The implementations are injected lazily through their client proxy, so selecting {@code RPS} never
 * initializes the FPE key and selecting {@code FPE} never contacts the RPS engine.
 */
@ApplicationScoped
public class EndPointTransformerProducer {

    private static final Logger LOG = LoggerFactory.getLogger(EndPointTransformerProducer.class);

    /**
     * Name of the implementation to run with, {@code RPS} or {@code FPE}.
     */
    @ConfigProperty(name = "proxy.transform.impl", defaultValue = TransformerImpl.RPS)
    String transformerImpl;

    @Inject
    @TransformerImpl(TransformerImpl.RPS)
    EndPointTransformer rpsTransformer;

    @Inject
    @TransformerImpl(TransformerImpl.FPE)
    EndPointTransformer fpeTransformer;

    /**
     * Produce the configured transformer.
     * <p>
     * An unknown value fails at startup rather than falling back to a default: running with another
     * tokenizer than the configured one would silently protect the data the wrong way, and the
     * protected data of the two implementations are not interchangeable.
     *
     * @return the transformer named by {@code proxy.transform.impl}
     * @throws IllegalStateException when the property does not name a known implementation
     */
    @Produces
    @ApplicationScoped
    public EndPointTransformer endPointTransformer() {
        String name = transformerImpl == null ? "" : transformerImpl.trim();

        if (TransformerImpl.RPS.equalsIgnoreCase(name)) {
            LOG.info("Transformations are performed by the RPS engine (proxy.transform.impl={}).", name);
            return rpsTransformer;
        }
        if (TransformerImpl.FPE.equalsIgnoreCase(name)) {
            LOG.info("Transformations are performed by Format Preserving Encryption (proxy.transform.impl={}).", name);
            return fpeTransformer;
        }

        throw new IllegalStateException("Unknown transformer implementation '" + name
                + "'. proxy.transform.impl accepts " + TransformerImpl.RPS + " or " + TransformerImpl.FPE + ".");
    }
}

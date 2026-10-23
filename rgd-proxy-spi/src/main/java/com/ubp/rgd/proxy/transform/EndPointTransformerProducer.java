package com.ubp.rgd.proxy.transform;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Provides the {@link EndPointTransformer} the proxy runs with, chosen by {@code proxy.transform.impl}.
 * <p>
 * The implementations are contributed by the modules {@code rgd-proxy-rps} and
 * {@code rgd-proxy-fpe}, each one qualified with {@link TransformerImpl}. None of them is therefore
 * the default bean, and application code injecting a plain {@code EndPointTransformer} gets the one
 * produced here. Leaving a module out of the application removes its implementation, no code
 * change needed.
 *
 * <pre>
 * proxy.transform.impl=RPS   # tokenization through the RegData RPS engine
 * proxy.transform.impl=FPE   # Format Preserving Encryption, no engine needed
 * </pre>
 *
 * The implementations are looked up lazily, so selecting {@code RPS} never initializes the FPE key
 * and selecting {@code FPE} never contacts the RPS engine.
 */
@ApplicationScoped
public class EndPointTransformerProducer {

    private static final Logger LOG = LoggerFactory.getLogger(EndPointTransformerProducer.class);

    /**
     * Name of the implementation to run with, such as {@code RPS} or {@code FPE}.
     */
    @ConfigProperty(name = "proxy.transform.impl", defaultValue = TransformerImpl.RPS)
    String transformerImpl;

    /**
     * Every transformer implementation present in the application.
     */
    @Inject
    @Any
    Instance<EndPointTransformer> transformers;

    /**
     * Check {@code proxy.transform.impl} when the application starts, without creating the
     * transformer: a wrong value must stop the application rather than fail its first request.
     *
     * @throws IllegalStateException when the property does not name an implementation present in the
     *                               application
     */
    void validateOnStartup(@Observes @Initialized(ApplicationScoped.class) Object event) {
        select(transformerImpl, availableImplementations());
    }

    /**
     * Produce the configured transformer.
     * <p>
     * An unknown value fails at startup rather than falling back to a default: running with another
     * tokenizer than the configured one would silently protect the data the wrong way, and the
     * protected data of the implementations are not interchangeable.
     *
     * @return the transformer named by {@code proxy.transform.impl}
     * @throws IllegalStateException when the property does not name an implementation present in the
     *                               application
     */
    @Produces
    @ApplicationScoped
    public EndPointTransformer endPointTransformer() {
        String selected = select(transformerImpl, availableImplementations());
        LOG.info("Transformations are performed by the {} transformer (proxy.transform.impl={}).",
                selected, transformerImpl);
        return transformers.select(new TransformerImpl.Literal(selected)).get();
    }

    /**
     * Match the configured implementation name against the available ones, ignoring case and
     * surrounding spaces.
     *
     * @param configured the value of {@code proxy.transform.impl}
     * @param available  the names of the implementations present in the application
     * @return the matching available name
     * @throws IllegalStateException when no available implementation has that name
     */
    static String select(String configured, Collection<String> available) {
        String name = configured == null ? "" : configured.trim();
        return available.stream()
                .filter(impl -> impl.equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown transformer implementation '" + name
                        + "'. proxy.transform.impl accepts "
                        + (available.isEmpty() ? "nothing: no transformer module is present in the application"
                                : String.join(" or ", available)) + "."));
    }

    /**
     * The names of the implementations present in the application, read from their
     * {@link TransformerImpl} qualifier without creating them.
     */
    List<String> availableImplementations() {
        TreeSet<String> names = new TreeSet<>();
        for (Instance.Handle<EndPointTransformer> handle : transformers.handles()) {
            handle.getBean().getQualifiers().stream()
                    .filter(TransformerImpl.class::isInstance)
                    .map(qualifier -> ((TransformerImpl) qualifier).value())
                    .filter(Objects::nonNull)
                    .forEach(names::add);
        }
        return List.copyOf(names);
    }
}

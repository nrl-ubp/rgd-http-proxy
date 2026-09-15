package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.*;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

@ApplicationScoped
@TransformerImpl(TransformerImpl.RPS)
public class RPSEndPointTransformer extends AbstractEndPointTransformer {

    private static final Logger LOG = LoggerFactory.getLogger(RPSEndPointTransformer.class);

    /**
     * The shape of an RPS token.
     * <p>
     * Tighter than the <code>RG{...}</code> delimiters alone: the engine writes a two character
     * mapping index, then an eight character identifier, then the transformed value. Matching that
     * shape rather than any pair of braces is what makes it safe to scan free text, such as a Flight
     * SQL result set, without mistaking an ordinary value for a token.
     */
    public static final Pattern TOKEN_PATTERN =
            Pattern.compile("(RG\\{[A-Z2-7x]{2}[a-zA-Z0-9\\-]{8}[a-zA-Z0-9]+\\})");

    @Inject
    RPSClientEngineProvider engineProvider;

    /**
     * {@inheritDoc}
     *
     * @return the RPS token pattern
     */
    @Override
    public Pattern tokenPattern() {
        return TOKEN_PATTERN;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}, the engine writes the mapping index in the first two characters
     */
    @Override
    public boolean supportsTokenMappingIndex() {
        return true;
    }

    /**
     * This method calls REGDATA to get the values transformed
     */
    public void transformData(IRPSValue<String>[] values, Context rightContext, ProcessingContext processingContext) throws Exception {
        RPSEngine engine = new RPSEngine(engineProvider.getClientEngineProvider(),
                new RPSEngineConverter(),
                new RPSEngineContextResolver(null));

        RequestContext requestContext = new RequestContext(engine, new RPSEngineContextResolver(null));

        requestContext
                .withRequest(
                        values,
                        rightContext,
                        processingContext,
                        null);

        // Calls the transformation API -> will lead to protect the data
        requestContext.transform();
    }
}

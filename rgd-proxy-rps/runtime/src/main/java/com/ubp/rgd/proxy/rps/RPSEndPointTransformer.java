package com.ubp.rgd.proxy.rps;

import ch.regdata.rps.engine.client.Context;
import ch.regdata.rps.engine.client.RPSEngine;
import ch.regdata.rps.engine.client.RPSEngineConverter;
import ch.regdata.rps.engine.client.RequestContext;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.transform.AbstractEndPointTransformer;
import com.ubp.rgd.proxy.transform.TransformerImpl;
import com.ubp.rgd.proxy.transform.model.EvidenceContext;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import com.ubp.rgd.proxy.transform.model.ProcessingContext;
import com.ubp.rgd.proxy.transform.model.RightContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
            Pattern.compile("(RG\\{[A-Z2-7x]{2}[a-zA-Z0-9\\-]{8}[a-zA-Z0-9]+})");

    private static final String ACTION_UNPROTECT = "Unprotect";

    @Inject
    RPSClientEngineProvider engineProvider;

    @Inject
    TokenSecretsManagerResolver tokenSecretsManagerResolver;

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
     * This method calls REGDATA to get the values transformed.
     * <p>
     * When the caller names no secrets manager and the action is {@code Unprotect}, as for the Flight
     * SQL result sets and the file transformations, the secrets manager of each token is resolved
     * from the token itself through {@link TokenSecretsManagerResolver}: the tokens may come from
     * different jurisdictions. The values are then grouped by secrets manager, with one engine call
     * per group. Every token is resolved before the first engine call, so a token that cannot be
     * resolved fails the whole call without anything being detokenized.
     * <p>
     * The values that are not tokens are sent along with the first group, as the engine leaves them
     * unchanged; with no token at all, a single call is made with the engine's default secrets manager.
     *
     * @param secretsManager the secrets manager the engine must use, {@code null} for its default one
     *                       or, when unprotecting, for the one that created each token
     * @throws SecretsManagerNotFoundException when the secrets manager of
     *         a token cannot be resolved
     */
    @Override
    public void transformData(MappedValue[] values, RightContext rightContext, ProcessingContext processingContext,
                              UUID secretsManager) throws Exception {
        if (secretsManager != null || !ACTION_UNPROTECT.equalsIgnoreCase(actionOf(processingContext))) {
            callEngine(values, rightContext, processingContext, secretsManager);
            return;
        }

        Map<UUID, List<MappedValue>> groups = groupBySecretsManager(values);
        for (Map.Entry<UUID, List<MappedValue>> group : groups.entrySet()) {
            LOG.debug("Unprotecting {} value(s) with the secrets manager {}", group.getValue().size(), group.getKey());
            callEngine(toArray(group.getValue()), rightContext, processingContext, group.getKey());
        }
    }

    /**
     * Group the values by the secrets manager that created their token, in order of first appearance.
     * Every token format the resolver knows is resolved (see
     * {@link TokenSecretsManagerResolver#isToken(String)}), each one through its cache, so a repeated
     * token only costs one database lookup. The values that are not tokens join the first group, or
     * a {@code null} group when there is no token.
     */
    private Map<UUID, List<MappedValue>> groupBySecretsManager(MappedValue[] values) {
        Map<UUID, List<MappedValue>> groups = new LinkedHashMap<>();
        List<MappedValue> notTokens = new ArrayList<>();
        for (MappedValue value : values) {
            String token = value == null ? null : value.getOriginal();
            if (!TokenSecretsManagerResolver.isToken(token)) {
                notTokens.add(value);
                continue;
            }

            String propertyName = value.getMapping().getPropertyName();
            String propertyClassName = value.getMapping().getClassName();
            UUID id = tokenSecretsManagerResolver.secretsManagerIdResolve(token, propertyClassName, propertyName);
            groups.computeIfAbsent(id, k -> new ArrayList<>()).add(value);
        }
        if (!notTokens.isEmpty()) {
            if (groups.isEmpty()) {
                groups.put(null, notTokens);
            } else {
                groups.values().iterator().next().addAll(notTokens);
            }
        }
        return groups;
    }

    private static MappedValue[] toArray(List<MappedValue> values) {
        return values.toArray(new MappedValue[0]);
    }

    /**
     * One call to the RPS engine, with a single secrets manager.
     * <p>
     * The values and contexts are converted to the RPS client model, and the transformed values (or
     * the errors) are copied back once the engine has answered.
     *
     * @param secretsManager the secrets manager the engine must use, {@code null} for its default one
     */
    protected void callEngine(MappedValue[] values, RightContext rightContext, ProcessingContext processingContext,
                              UUID secretsManager) throws Exception {
        RPSValue[] rpsValues = new RPSValue[values.length];
        for (int i = 0; i < values.length; i++) {
            MappedValue value = values[i];
            rpsValues[i] = new RPSValue(
                    new RPSMapping(value.getMapping().getClassName(), value.getMapping().getPropertyName()),
                    value.getOriginal());
        }

        RPSEngine engine = new RPSEngine(engineProvider.getClientEngineProvider(),
                new RPSEngineConverter(),
                new RPSEngineContextResolver(null));

        RequestContext requestContext = new RequestContext(engine, new RPSEngineContextResolver(null));

        requestContext
                .withRequest(
                        rpsValues,
                        toRpsContext(rightContext, new ch.regdata.rps.engine.client.enginecontext.RightContext()),
                        toRpsContext(processingContext, new ch.regdata.rps.engine.client.enginecontext.ProcessingContext()),
                        null,
                        secretsManager);

        // Calls the transformation API -> will lead to protect the data
        requestContext.transform();

        for (int i = 0; i < values.length; i++) {
            values[i].setTransformed(rpsValues[i].getTransformed());
            if (rpsValues[i].getError() != null) {
                values[i].setError(rpsValues[i].getError().getMessage());
            }
        }
    }

    /**
     * Copy the evidences of a context to its RPS client counterpart; {@code null} stays {@code null}.
     */
    private static <C extends Context> C toRpsContext(EvidenceContext context, C rpsContext) {
        if (context == null) {
            return null;
        }
        if (context.getEvidences() != null) {
            context.getEvidences().forEach(evidence -> rpsContext.addEvidence(
                    new ch.regdata.rps.engine.client.Evidence(evidence.getName(), evidence.getValue())));
        }
        return rpsContext;
    }
}

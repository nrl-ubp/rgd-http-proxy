package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.*;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import com.ubp.rgd.proxy.services.TokenSecretsManagerResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
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
     * @throws com.ubp.rgd.proxy.exception.SecretsManagerNotFoundException when the secrets manager of
     *         a token cannot be resolved
     */
    @Override
    public void transformData(IRPSValue<String>[] values, Context rightContext, ProcessingContext processingContext,
                              UUID secretsManager) throws Exception {
        if (secretsManager != null || !ACTION_UNPROTECT.equalsIgnoreCase(actionOf(processingContext))) {
            callEngine(values, rightContext, processingContext, secretsManager);
            return;
        }

        Map<UUID, List<IRPSValue<String>>> groups = groupBySecretsManager(values);
        for (Map.Entry<UUID, List<IRPSValue<String>>> group : groups.entrySet()) {
            LOG.debug("Unprotecting {} value(s) with the secrets manager {}", group.getValue().size(), group.getKey());
            callEngine(toArray(group.getValue()), rightContext, processingContext, group.getKey());
        }
    }

    /**
     * Group the values by the secrets manager that created their token, in order of first appearance.
     * Each distinct token is resolved once. The values that are not tokens join the first group, or
     * a {@code null} group when there is no token.
     */
    private Map<UUID, List<IRPSValue<String>>> groupBySecretsManager(IRPSValue<String>[] values) {
        Map<String, UUID> resolved = new HashMap<>();
        Map<UUID, List<IRPSValue<String>>> groups = new LinkedHashMap<>();
        List<IRPSValue<String>> notTokens = new ArrayList<>();
        for (IRPSValue<String> value : values) {
            String token = value == null ? null : value.getOriginal();
            if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
                notTokens.add(value);
                continue;
            }
            UUID id = resolved.computeIfAbsent(token, tokenSecretsManagerResolver::secretsManagerIdResolve);
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

    @SuppressWarnings("unchecked")
    private static IRPSValue<String>[] toArray(List<IRPSValue<String>> values) {
        return values.toArray(new IRPSValue[0]);
    }

    /**
     * One call to the RPS engine, with a single secrets manager.
     *
     * @param secretsManager the secrets manager the engine must use, {@code null} for its default one
     */
    protected void callEngine(IRPSValue<String>[] values, Context rightContext, ProcessingContext processingContext,
                              UUID secretsManager) throws Exception {
        RPSEngine engine = new RPSEngine(engineProvider.getClientEngineProvider(),
                new RPSEngineConverter(),
                new RPSEngineContextResolver(null));

        RequestContext requestContext = new RequestContext(engine, new RPSEngineContextResolver(null));

        requestContext
                .withRequest(
                        values,
                        rightContext,
                        processingContext,
                        null,
                        secretsManager);

        // Calls the transformation API -> will lead to protect the data
        requestContext.transform();
    }
}

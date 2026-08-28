package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.Context;
import ch.regdata.rps.engine.client.Evidence;
import ch.regdata.rps.engine.client.RPSEngine;
import ch.regdata.rps.engine.client.RPSEngineConverter;
import ch.regdata.rps.engine.client.RequestContext;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.enginecontext.RightContext;
import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.security.SecurityContext;
import com.ubp.rgd.proxy.transform.RPSClientEngineProvider;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import com.ubp.rgd.proxy.transform.api.TransformRequest;
import com.ubp.rgd.proxy.transform.api.TransformResponse;
import com.ubp.rgd.proxy.transform.api.TransformResultSet;
import com.ubp.rgd.proxy.transform.api.TransformSet;
import com.ubp.rgd.proxy.transform.api.TransformValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service exposing generic RPS transformation (Protect / Unprotect) of a list of value sets.
 * <p>
 * Each {@link TransformSet} carries an {@code action}, a {@code target} and a {@code jurisdiction}
 * code that are passed to the RPS engine as processing-context evidences ({@code Action},
 * {@code Target}, {@code Jurisdiction}). A value may optionally carry an {@code extractRegExp} used,
 * for protection, to tokenize the value group by group while preserving its original format. For
 * unprotection, tokens are located by their fixed delimiter ({@code RG{...}}).
 */
@ApplicationScoped
public class TransformService {

    private static final Logger LOG = Logger.getLogger(TransformService.class);

    private static final String ACTION_PROTECT = "Protect";
    private static final String ACTION_UNPROTECT = "Unprotect";

    /** Fixed delimiter pattern of an RPS token ({@code RG{...}}), used to locate tokens on unprotect. */
    static final Pattern TOKEN_PATTERN = Pattern.compile("RG\\{[^}]*\\}");

    @ConfigProperty(name = "proxy.transform.endpoint.right-context-target")
    String rightContextTarget;

    @ConfigProperty(name = "proxy.transform.endpoint.right-context-module")
    String rightContextModule;

    @ConfigProperty(name = "proxy.transform.endpoint.right-context-right")
    String rightContextRight;


    @Inject
    RPSClientEngineProvider rpsClientEngineProvider;

    @Inject
    SecurityContext securityContext;

    /**
     * Comma-separated list of SPNs or usernames allowed to call the transform endpoint.
     */
    @ConfigProperty(name = "proxy.transform.endpoint.authorized-spn", defaultValue = "")
    String authorizedSpn;

    /**
     * Flag mirroring the pre-filter authentication switch. When authentication is globally disabled
     * (e.g. local dev), the endpoint authorization check is skipped.
     */
    @ConfigProperty(name = "proxy.prefilter.auth-enabled", defaultValue = "true")
    String preFilterAuthEnabled;

    /**
     * Transform every value of every set through the RPS engine.
     * @param request the transform request (list of sets)
     * @return the transformed values grouped per set, in input order
     * @throws RPSTransformException on invalid input or any RPS transformation error
     */
    public TransformResponse transform(TransformRequest request) throws RPSTransformException {
        // Only authorized SPNs / usernames may call the transform endpoint.
        checkAuthorization();

        if (request == null || request.getSets() == null) {
            throw new RPSTransformException("The transform request or its sets are null.");
        }

        TransformResponse response = new TransformResponse();
        for (TransformSet set : request.getSets()) {
            response.getResults().add(transformSet(set));
        }
        return response;
    }

    /**
     * Ensure the current caller is allowed to use the transform endpoint. The caller's username (from
     * the security context) must be listed in {@code proxy.transform.endpoint.authorized-spn}.
     * <p>
     * The check is skipped when authentication is globally disabled
     * ({@code proxy.prefilter.auth-enabled=false}). When authentication is enabled but no authorized
     * caller is configured, access is denied (fail closed).
     *
     * @throws ForbiddenException if the caller is not authenticated or not authorized
     */
    void checkAuthorization() {
        if (!"true".equalsIgnoreCase(preFilterAuthEnabled)) {
            LOG.warn("Prefilter auth DISABLED. Not checking authorization for the TRANSFORM endpoint.");
            return;
        }

        if (securityContext == null || securityContext.getToken() == null) {
            LOG.error("No security context found while calling the transform endpoint.");
            throw new ForbiddenException("No authenticated caller for the transform endpoint.");
        }

        Set<String> authorized = parseAuthorizedList(authorizedSpn);
        if (authorized.isEmpty()) {
            LOG.error("No authorized SPN/username configured for the transform endpoint " +
                    "(proxy.transform.endpoint.authorized-spn). Denying access.");
            throw new ForbiddenException("Transform endpoint has no authorized caller configured.");
        }

        String userName = securityContext.getToken().getUser();
        if (!isAuthorized(userName, authorized)) {
            LOG.errorf("User %s is not authorized to call the transform endpoint.", userName);
            throw new ForbiddenException(
                    String.format("User %s is not authorized to call the transform endpoint.", userName));
        }

        LOG.debugf("User %s is authorized to call the transform endpoint.", userName);
    }

    /**
     * Parse a comma-separated list of SPNs / usernames into a normalized (lower-cased, trimmed) set.
     * @param csv the comma-separated configuration value (may be null/empty)
     * @return the set of authorized entries (empty when none configured)
     */
    static Set<String> parseAuthorizedList(String csv) {
        Set<String> result = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .forEach(result::add);
        return result;
    }

    /**
     * @param user the caller's username / SPN (may be null)
     * @param authorized the normalized set of authorized entries
     * @return true when the (case-insensitive) user is contained in the authorized set
     */
    static boolean isAuthorized(String user, Set<String> authorized) {
        return user != null && authorized.contains(user.toLowerCase());
    }

    private TransformResultSet transformSet(TransformSet set) throws RPSTransformException {
        if (set == null || set.getValues() == null) {
            throw new RPSTransformException("A transform set or its values are null.");
        }

        // Build, per value, the segments to transform (word by word for protect with a regex, token
        // by token for unprotect) and one RPSValue per segment. All the set's RPSValues are batched
        // into a single engine call.
        List<ValuePlan> plans = new ArrayList<>(set.getValues().size());
        List<RPSValue> flatValues = new ArrayList<>();

        for (TransformValue value : set.getValues()) {
            ValuePlan plan = buildValuePlan(set.getAction(), value);
            plans.add(plan);
            flatValues.addAll(plan.rpsValues);
        }

        if (!flatValues.isEmpty()) {
            try {
                transformData(
                        rpsClientEngineProvider.getClientEngineProvider(),
                        flatValues.toArray(new RPSValue[0]),
                        buildRightContext(),
                        buildProcessingContext(set));
            } catch (RPSTransformException e) {
                throw e;
            } catch (Exception e) {
                LOG.error("Transform exception: ", e);
                throw new RPSTransformException(e);
            }
        }

        List<String> results = new ArrayList<>(plans.size());
        for (ValuePlan plan : plans) {
            results.add(plan.reassemble());
        }
        return new TransformResultSet(results);
    }

    /**
     * Build the right context for RoseGarden proxy taken from the application.properties configuration file
     * @return the right context with evidences according to the config
     */
    private RightContext buildRightContext() {
        RightContext context = new RightContext();
        context.addEvidence(new Evidence("Target", this.rightContextTarget));
        context.addEvidence(new Evidence("Module", this.rightContextModule));
        context.addEvidence(new Evidence("Right",  this.rightContextRight));
        return context;
    }

    /**
     * Build the transformation plan for a single value: the extraction pattern (if any), the ordered
     * list of segments to transform and one {@link RPSValue} per segment.
     */
    ValuePlan buildValuePlan(String action, TransformValue value) throws RPSTransformException {
        if (value == null || value.getValue() == null) {
            throw new RPSTransformException("A transform value is null.");
        }

        RPSMapping mapping = new RPSMapping(value.getClassName(), value.getPropertyName());
        Pattern pattern = extractionPattern(action, value.getExtractRegExp());

        List<RPSValue> rpsValues = new ArrayList<>();
        if (pattern == null) {
            // Whole value transformed as a single segment.
            rpsValues.add(new RPSValue(mapping, value.getValue()));
        } else {
            Matcher matcher = pattern.matcher(value.getValue());
            while (matcher.find()) {
                rpsValues.add(new RPSValue(mapping, matcher.group()));
            }
        }

        return new ValuePlan(value.getValue(), pattern, rpsValues);
    }

    /**
     * Choose the extraction pattern for a value depending on the action:
     * <ul>
     *     <li>Protect: the value's {@code extractRegExp} when present (word-by-word tokenization),
     *         otherwise {@code null} (whole value).</li>
     *     <li>Unprotect: the fixed {@link #TOKEN_PATTERN} to locate {@code RG{...}} tokens.</li>
     *     <li>Any other action: {@code null} (whole value).</li>
     * </ul>
     */
    private Pattern extractionPattern(String action, String extractRegExp) throws RPSTransformException {
        if (ACTION_PROTECT.equalsIgnoreCase(action)) {
            if (extractRegExp == null || extractRegExp.isEmpty()) {
                return null;
            }
            try {
                return Pattern.compile(extractRegExp);
            } catch (Exception e) {
                throw new RPSTransformException("Invalid extract-regex: " + extractRegExp);
            }
        }
        if (ACTION_UNPROTECT.equalsIgnoreCase(action)) {
            return TOKEN_PATTERN;
        }
        return null;
    }

    private ProcessingContext buildProcessingContext(TransformSet set) {
        ProcessingContext processingContext = new ProcessingContext();
        addEvidence(processingContext, "Action", set.getAction());
        addEvidence(processingContext, "Target", set.getTarget());
        addEvidence(processingContext, "Module", set.getModule());
        processingContext.getEvidences()
                .forEach(e -> LOG.debugf("Processing context: %s = %s", e.getName(), e.getValue()));
        return processingContext;
    }

    private void addEvidence(ProcessingContext processingContext, String name, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        Evidence evidence = new Evidence();
        evidence.setName(name);
        evidence.setValue(value);
        processingContext.addEvidence(evidence);
    }

    /**
     * Calls the RPS engine to transform the given values in place.
     */
    private void transformData(HttpClientEngineProvider engineProvider, IRPSValue<String>[] values,
                               Context rightContext, ProcessingContext processingContext) throws Exception {
        RPSEngine engine = new RPSEngine(engineProvider,
                new RPSEngineConverter(),
                new RPSEngineContextResolver(null));

        RequestContext requestContext = new RequestContext(engine, new RPSEngineContextResolver(null));
        requestContext.withRequest(values, rightContext, processingContext, null);
        requestContext.transform();
    }

    /**
     * Plan to rebuild a single value from its transformed segments while preserving the original
     * format (surrounding characters, separators, token order).
     */
    static final class ValuePlan {
        private final String originalValue;
        private final Pattern pattern;
        final List<RPSValue> rpsValues;

        ValuePlan(String originalValue, Pattern pattern, List<RPSValue> rpsValues) {
            this.originalValue = originalValue;
            this.pattern = pattern;
            this.rpsValues = rpsValues;
        }

        String reassemble() throws RPSTransformException {
            if (rpsValues.isEmpty()) {
                // No segment matched (e.g. regex with no match): return the value unchanged.
                return originalValue;
            }
            if (pattern == null) {
                // Single whole-value segment.
                return requireTransformed(rpsValues.getFirst());
            }

            Matcher matcher = pattern.matcher(originalValue);
            StringBuilder rebuilt = new StringBuilder();
            int index = 0;
            while (matcher.find() && index < rpsValues.size()) {
                String transformed = requireTransformed(rpsValues.get(index++));
                matcher.appendReplacement(rebuilt, Matcher.quoteReplacement(transformed));
            }
            matcher.appendTail(rebuilt);
            return rebuilt.toString();
        }

        private String requireTransformed(RPSValue rpsValue) throws RPSTransformException {
            String transformed = rpsValue.getTransformed();
            if (transformed == null) {
                throw new RPSTransformException(
                        "Transformation did not return a value for: " + rpsValue.getOriginal());
            }
            return transformed;
        }
    }
}

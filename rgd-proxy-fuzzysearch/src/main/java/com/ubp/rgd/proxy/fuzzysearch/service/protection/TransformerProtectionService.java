package com.ubp.rgd.proxy.fuzzysearch.service.protection;

import com.ubp.rgd.proxy.transform.EndPointTransformer;
import com.ubp.rgd.proxy.transform.TransformException;
import com.ubp.rgd.proxy.transform.ValuePlan;
import com.ubp.rgd.proxy.transform.model.Evidence;
import com.ubp.rgd.proxy.transform.model.EvidenceContext;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import com.ubp.rgd.proxy.transform.model.ProcessingContext;
import com.ubp.rgd.proxy.transform.model.RightContext;
import com.ubp.rgd.proxy.transform.model.ValueMapping;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.ubp.rgd.proxy.fuzzysearch.entity.PersonEntity;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Pattern;

/**
 * {@link ProtectionService} backed by the {@link EndPointTransformer} of the rgd-http-proxy, the one
 * selected by {@code proxy.transform.impl}: Format Preserving Encryption (FF1) or the RegData RPS
 * engine.
 * <p>
 * Values are protected and unprotected exactly the way the proxy does it, so the data it wrote is
 * readable here and the other way round:
 * <ul>
 *     <li>each column is transformed with the RPS class and property of the Flight SQL mapping file
 *     of the proxy, see {@link ColumnMappingConfig};</li>
 *     <li>a value is protected as a whole, like the {@code transform()} SQL extension does;</li>
 *     <li>a value is unprotected token by token, the tokens being located with the pattern of the
 *     transformer, which also finds the FPE dates that carry no <code>RG{...}</code> wrapper.</li>
 * </ul>
 * The contexts are the {@code before} (protect) and {@code after} (unprotect) evidences of the same
 * file, the {@code Action} evidence being forced to the direction.
 */
@ApplicationScoped
public class TransformerProtectionService implements ProtectionService {

    private static final Logger LOG = LoggerFactory.getLogger(TransformerProtectionService.class);

    private static final String ACTION_PROTECT = "Protect";
    private static final String ACTION_UNPROTECT = "Unprotect";

    @Inject
    EndPointTransformer transformer;

    /** The Flight SQL mapping file of the proxy, holding the column mappings and the contexts. */
    @ConfigProperty(name = "fuzzy-search.mapping-config-file",
            defaultValue = "${proxy.flight-sql.mapping-config-file:./config/flight_sql_mapping_config.json}")
    String mappingConfigFile;

    private ColumnMappingConfig mappings;

    /** Build a service outside of CDI, for the tests and the batch tools. */
    public static TransformerProtectionService of(EndPointTransformer transformer, ColumnMappingConfig mappings) {
        TransformerProtectionService service = new TransformerProtectionService();
        service.transformer = transformer;
        service.mappings = mappings;
        service.checkMappings();
        return service;
    }

    @PostConstruct
    void init() {
        mappings = ColumnMappingConfig.load(mappingConfigFile);
        checkMappings();
        LOG.info("Protecting the " + PersonEntity.TABLE + " columns with the mappings of " + mappingConfigFile);
    }

    /** Every protected column must be mapped: fail at startup rather than on the first request. */
    private void checkMappings() {
        for (String column : PersonEntity.PROTECTED_COLUMNS) {
            mappings.require(PersonEntity.TABLE, column);
        }
    }

    @Override
    public List<String> tokenize(String clear) {
        if (clear == null || clear.isBlank()) {
            return List.of();
        }
        return Arrays.stream(clear.trim().split("\\s+"))
                .map(t -> t.toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty())
                .toList();
    }

    @Override
    public List<String> protectAll(List<ColumnValue> values) {
        List<ValuePlan> plans = new ArrayList<>(values.size());
        for (ColumnValue value : values) {
            // Whole value, exactly as the transform() SQL extension of the proxy protects it.
            plans.add(value.value() == null ? null : ValuePlan.of(mappingOf(value.column()), value.value(), null));
        }
        return run(plans, ACTION_PROTECT, mappings.before());
    }

    @Override
    public List<String> unprotectAll(List<ColumnValue> values) {
        Pattern tokens;
        try {
            tokens = ValuePlan.extractionPattern(ACTION_UNPROTECT, null,
                    transformer.preservesValueTypes() ? transformer.tokenPattern() : null);
        } catch (TransformException e) {
            throw new IllegalStateException(e);
        }
        List<ValuePlan> plans = new ArrayList<>(values.size());
        for (ColumnValue value : values) {
            plans.add(value.value() == null ? null : ValuePlan.of(mappingOf(value.column()), value.value(), tokens));
        }
        return run(plans, ACTION_UNPROTECT, mappings.after());
    }

    /**
     * Transform every segment of the plans in a single call, then rebuild each value.
     */
    private List<String> run(List<ValuePlan> plans, String action, ColumnMappingConfig.Phase phase) {
        List<MappedValue> flat = new ArrayList<>();
        for (ValuePlan plan : plans) {
            if (plan != null) {
                flat.addAll(plan.rpsValues());
            }
        }
        if (!flat.isEmpty()) {
            try {
                transformer.transformData(flat.toArray(new MappedValue[0]),
                        context(new RightContext(), phase.rightContext, null),
                        context(new ProcessingContext(), phase.processingContext, action));
            } catch (Exception e) {
                throw new IllegalStateException(action + " failed: " + e.getMessage(), e);
            }
        }

        List<String> results = new ArrayList<>(plans.size());
        for (ValuePlan plan : plans) {
            try {
                results.add(plan == null ? null : plan.reassemble());
            } catch (TransformException e) {
                throw new IllegalStateException(action + " failed: " + e.getMessage(), e);
            }
        }
        return results;
    }

    private ValueMapping mappingOf(String column) {
        return mappings.require(PersonEntity.TABLE, column);
    }

    /**
     * Fill a context with the evidences of a phase, the action, when given, replacing the one of the
     * file: the direction is decided by the caller, not by the configuration.
     */
    private static <T extends EvidenceContext> T context(T context, Map<String, String> evidences, String action) {
        if (evidences != null) {
            evidences.forEach((name, value) -> {
                if (name != null && !name.isBlank() && value != null && !value.isEmpty()
                        && !(action != null && "Action".equalsIgnoreCase(name))) {
                    context.addEvidence(new Evidence(name, value));
                }
            });
        }
        if (action != null) {
            context.addEvidence(new Evidence("Action", action));
        }
        return context;
    }
}

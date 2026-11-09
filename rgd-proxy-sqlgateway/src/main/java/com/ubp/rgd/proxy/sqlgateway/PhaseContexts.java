package com.ubp.rgd.proxy.sqlgateway;

import com.ubp.rgd.proxy.sqlgateway.config.PhaseConfig;
import com.ubp.rgd.proxy.transform.model.Evidence;
import com.ubp.rgd.proxy.transform.model.EvidenceContext;
import com.ubp.rgd.proxy.transform.model.ProcessingContext;
import com.ubp.rgd.proxy.transform.model.RightContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Builds the transformer contexts of a phase of the mapping configuration: {@code before} for the
 * values a statement carries to the database, {@code after} for the values a result brings back.
 */
public final class PhaseContexts {

    private static final Logger LOG = LoggerFactory.getLogger(PhaseContexts.class);

    private PhaseContexts() {
    }

    /**
     * @param phase the phase, may be null
     * @return the right context made of the right-context evidences of the phase
     */
    public static RightContext rightContext(PhaseConfig phase) {
        RightContext context = new RightContext();
        if (phase != null) {
            addEvidences(context, phase.getRightContextEvidences());
        }
        return context;
    }

    /**
     * The processing context of a phase. A transformer takes its direction from the {@code Action}
     * evidence: when the phase declares none, the action implied by the phase is added.
     *
     * @param phase the phase, may be null
     * @param defaultAction {@code Protect} for the {@code before} phase, {@code Unprotect} for the
     *        {@code after} one
     * @param phaseName names the phase in the warning, such as {@code TDS gateway 'after'}
     * @return the processing context
     */
    public static ProcessingContext processingContext(PhaseConfig phase, String defaultAction, String phaseName) {
        ProcessingContext context = new ProcessingContext();
        if (phase != null) {
            addEvidences(context, phase.getProcessingContextEvidences());
        }
        boolean hasAction = context.getEvidences().stream()
                .anyMatch(evidence -> "Action".equalsIgnoreCase(evidence.getName()));
        if (!hasAction) {
            LOG.warn("The {} phase declares no Action evidence. Using {}.", phaseName, defaultAction);
            context.addEvidence(new Evidence("Action", defaultAction));
        }
        return context;
    }

    private static void addEvidences(EvidenceContext context, Map<String, String> evidences) {
        if (evidences == null) {
            return;
        }
        evidences.forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null && !value.isEmpty()) {
                context.addEvidence(new Evidence(name, value));
            }
        });
    }
}

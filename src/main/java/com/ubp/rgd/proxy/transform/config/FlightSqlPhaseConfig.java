package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration of one phase of a Flight SQL exchange.
 * <p>
 * A query goes through the proxy twice: the statement is sent to the database server, and the result
 * set comes back. The two directions do not use the same RPS contexts — typically {@code Protect} on
 * the way in and {@code Unprotect} on the way out — so each phase carries its own pair of contexts:
 * <ul>
 *   <li>{@code before} — applied to the query before it reaches the database server, which is the
 *       {@code transform()} SQL extension</li>
 *   <li>{@code after} — applied to the result set, which is the detokenization</li>
 * </ul>
 *
 * @see FlightSqlMappingConfig
 */
public class FlightSqlPhaseConfig {

    /**
     * Whether the phase is performed at all. Defaults to {@code true}, so a phase that is present but
     * says nothing about it, or a configuration built programmatically, behaves like a phase that is
     * meant to run.
     */
    @JsonProperty(value = "active")
    private boolean active = true;

    @JsonProperty(value = "right-context")
    private Map<String, String> rightContextEvidences = new HashMap<>();

    @JsonProperty(value = "processing-context")
    private Map<String, String> processingContextEvidences = new HashMap<>();

    public FlightSqlPhaseConfig() {
        // for Jackson and for the default phases
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Map<String, String> getRightContextEvidences() {
        return rightContextEvidences;
    }

    public void setRightContextEvidences(Map<String, String> rightContextEvidences) {
        this.rightContextEvidences = rightContextEvidences;
    }

    public Map<String, String> getProcessingContextEvidences() {
        return processingContextEvidences;
    }

    public void setProcessingContextEvidences(Map<String, String> processingContextEvidences) {
        this.processingContextEvidences = processingContextEvidences;
    }

    /**
     * @return the {@code Action} evidence of the processing context, or {@code null} when none is
     *         declared. The lookup ignores the case of the evidence name.
     */
    public String getAction() {
        if (processingContextEvidences == null) {
            return null;
        }
        return processingContextEvidences.entrySet().stream()
                .filter(entry -> entry.getKey() != null && "Action".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}

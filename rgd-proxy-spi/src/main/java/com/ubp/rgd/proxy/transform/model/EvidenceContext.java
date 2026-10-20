package com.ubp.rgd.proxy.transform.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An ordered list of {@link Evidence}, the common base of the right and processing contexts.
 */
public abstract class EvidenceContext {

    private List<Evidence> evidences = new ArrayList<>();

    protected EvidenceContext() {
    }

    protected EvidenceContext(List<Evidence> evidences) {
        setEvidences(evidences);
    }

    public List<Evidence> getEvidences() {
        return evidences;
    }

    public void setEvidences(List<Evidence> evidences) {
        this.evidences = evidences == null ? new ArrayList<>() : new ArrayList<>(evidences);
    }

    public void addEvidence(Evidence evidence) {
        evidences.add(evidence);
    }

    /**
     * Get the value of the first evidence of the given name, ignoring the case of the name.
     *
     * @param name the evidence name
     * @return its value, {@code null} when there is no such evidence
     */
    public String get(String name) {
        return evidences.stream()
                .filter(evidence -> evidence != null && evidence.getName() != null
                        && evidence.getName().equalsIgnoreCase(name))
                .map(Evidence::getValue)
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return Objects.equals(evidences, ((EvidenceContext) o).evidences);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), evidences);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + evidences;
    }
}

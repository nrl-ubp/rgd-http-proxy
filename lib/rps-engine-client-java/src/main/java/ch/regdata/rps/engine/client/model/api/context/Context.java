package ch.regdata.rps.engine.client.model.api.context;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Context {
    private UUID guid;
    private List<Evidence> evidences = new ArrayList<>();

    public UUID getGuid() {
        return guid;
    }

    public void setGuid(UUID guid) {
        this.guid = guid;
    }

    public List<Evidence> getEvidences() {
        return evidences;
    }

    public void setEvidences(List<Evidence> evidences) {
        this.evidences = evidences;
    }

    public void addEvidence(Evidence evidence) {
        if (evidence != null) {
            if (evidences == null)
                evidences = new ArrayList<>();
            evidences.add(evidence);
        }
    }

    @Override
    public int hashCode() {
        return evidences.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Context context = (Context) o;

        return evidences.equals(context.evidences);
    }
}

package ch.regdata.rps.engine.client;


import java.util.ArrayList;
import java.util.List;

public class Context {

    private List<Evidence> evidences = new ArrayList<>();

    public Context(List<Evidence> evidences) {
        setEvidences(evidences);
    }

    public Context() {

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

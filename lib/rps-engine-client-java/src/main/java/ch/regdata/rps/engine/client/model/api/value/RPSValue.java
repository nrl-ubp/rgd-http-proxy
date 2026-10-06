package ch.regdata.rps.engine.client.model.api.value;

import ch.regdata.rps.engine.client.mapping.RPSMapping;

public class RPSValue extends RPSValueBase implements IRPSValue<String> {

    private String original;
    private String transformed;

    public RPSValue(RPSMapping mapping, String originalValue) {
        super(mapping);
        this.original = originalValue;
    }

    public String getValue() {
        return original;
    }

    public void setValue(String value) {
        setTransformed(value);
    }

    public String getOriginal() {
        return original;
    }

    public void setOriginal(String original) {
        this.original = original;
    }

    public boolean isEmpty() {
        return original != null && original.isEmpty();
    }

    public String getTransformed() {
        return transformed;
    }

    public void setTransformed(String transformed) {
        this.transformed = transformed;
    }
}

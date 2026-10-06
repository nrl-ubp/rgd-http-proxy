package ch.regdata.rps.engine.client.model.api.value;

import ch.regdata.rps.engine.client.mapping.RPSMapping;

public class RPSIntValue extends RPSValueBase implements IRPSValue<Integer> {

    private Integer original;
    private Integer transformed;

    public RPSIntValue(RPSMapping mapping, Integer originalValue) {
        super(mapping);
        this.original = originalValue;
    }

    public String getValue() {
        return original != null ? original.toString() : null;
    }

    public void setValue(String value) {
        setTransformed(Integer.valueOf(value));
    }

    public Integer getOriginal() {
        return original;
    }

    public void setOriginal(Integer original) {
        this.original = original;
    }

    public Integer getTransformed() {
        return transformed;
    }

    public void setTransformed(Integer transformed) {
        this.transformed = transformed;
    }

}

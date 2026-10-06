package ch.regdata.rps.engine.client.model.api.value;

import ch.regdata.rps.engine.client.mapping.RPSMapping;

public class RPSDoubleValue extends RPSValueBase implements IRPSValue<Double> {

    private Double original;
    private Double transformed;

    public RPSDoubleValue(RPSMapping mapping, Double originalValue) {
        super(mapping);
        this.original = originalValue;
    }

    public String getValue() {
        return original != null ? original.toString() : null;
    }

    public void setValue(String value) {
        setTransformed(Double.valueOf(value));
    }

    public Double getOriginal() {
        return original;
    }

    public void setOriginal(Double original) {
        this.original = original;
    }

    public Double getTransformed() {
        return transformed;
    }

    public void setTransformed(Double transformed) {
        this.transformed = transformed;
    }

}

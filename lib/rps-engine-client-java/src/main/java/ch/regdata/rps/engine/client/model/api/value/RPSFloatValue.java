package ch.regdata.rps.engine.client.model.api.value;

import ch.regdata.rps.engine.client.mapping.RPSMapping;

public class RPSFloatValue extends RPSValueBase implements IRPSValue<Float> {

    private Float original;
    private Float transformed;

    public RPSFloatValue(RPSMapping mapping, Float originalValue) {
        super(mapping);
        this.original = originalValue;
    }

    public String getValue() {
        return original != null ? original.toString() : null;
    }

    public void setValue(String value) {
        setTransformed(Float.valueOf(value));
    }

    public Float getOriginal() {
        return original;
    }

    public void setOriginal(Float original) {
        this.original = original;
    }

    public Float getTransformed() {
        return transformed;
    }

    public void setTransformed(Float transformed) {
        this.transformed = transformed;
    }

}

package ch.regdata.rps.engine.client.model.api.value;

import ch.regdata.rps.engine.client.mapping.RPSMapping;

public class RPSLongValue extends RPSValueBase implements IRPSValue<Long> {

    private Long original;
    private Long transformed;

    public RPSLongValue(RPSMapping mapping, Long originalValue) {
        super(mapping);
        this.original = originalValue;
    }

    public String getValue() {
        return original != null ? original.toString() : null;
    }

    public void setValue(String value) {
        setTransformed(Long.valueOf(value));
    }

    public Long getOriginal() {
        return original;
    }

    public void setOriginal(Long original) {
        this.original = original;
    }

    public Long getTransformed() {
        return transformed;
    }

    public void setTransformed(Long transformed) {
        this.transformed = transformed;
    }

}

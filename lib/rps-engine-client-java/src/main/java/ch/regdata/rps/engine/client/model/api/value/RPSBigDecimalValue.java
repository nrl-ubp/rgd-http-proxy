package ch.regdata.rps.engine.client.model.api.value;

import ch.regdata.rps.engine.client.mapping.RPSMapping;

import java.math.BigDecimal;

public class RPSBigDecimalValue extends RPSValueBase implements IRPSValue<BigDecimal> {

    private BigDecimal original;
    private BigDecimal transformed;

    public RPSBigDecimalValue(RPSMapping mapping, BigDecimal originalValue) {
        super(mapping);
        this.original = originalValue;
    }

    public String getValue() {
        return original != null ? original.toString() : null;
    }

    public void setValue(String value) {
        setTransformed(new BigDecimal(Long.valueOf(value)));
    }

    public BigDecimal getOriginal() {
        return original;
    }

    public void setOriginal(BigDecimal original) {
        this.original = original;
    }

    public BigDecimal getTransformed() {
        return transformed;
    }

    public void setTransformed(BigDecimal transformed) {
        this.transformed = transformed;
    }
}

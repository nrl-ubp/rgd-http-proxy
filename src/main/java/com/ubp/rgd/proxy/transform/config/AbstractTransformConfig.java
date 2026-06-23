package com.ubp.rgd.proxy.transform.config;

import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.fasterxml.jackson.annotation.JsonProperty;

public abstract class AbstractTransformConfig {
    @JsonProperty(value="rps-class-name")
    private String rpsClassName = "";

    @JsonProperty(value="rps-property-name")
    private String rpsPropertyName= "";

    public String getRpsClassName() {
        return rpsClassName;
    }

    public void setRpsClassName(String rpsClassName) {
        this.rpsClassName = rpsClassName;
    }

    public String getRpsPropertyName() {
        return rpsPropertyName;
    }

    public void setRpsPropertyName(String rpsPropertyName) {
        this.rpsPropertyName = rpsPropertyName;
    }

    public RPSValue getRPSValue(String originalValue) {
        return new RPSValue(new RPSMapping(this.rpsClassName, this.rpsPropertyName), originalValue);
    }
}

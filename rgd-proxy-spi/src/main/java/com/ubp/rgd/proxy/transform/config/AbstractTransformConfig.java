package com.ubp.rgd.proxy.transform.config;

import com.ubp.rgd.proxy.transform.model.ValueMapping;
import com.ubp.rgd.proxy.transform.model.MappedValue;
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

    public MappedValue getRPSValue(String originalValue) {
        return new MappedValue(new ValueMapping(this.rpsClassName, this.rpsPropertyName), originalValue);
    }
}

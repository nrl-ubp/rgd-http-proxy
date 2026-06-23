package com.ubp.rgd.proxy.services.wdx1.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ubp.rgd.proxy.transform.config.AbstractTransformConfig;

public class WDX1ConcatRpsMapping extends AbstractTransformConfig {

    @JsonProperty("rps-mapping-type")
    private String type;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}

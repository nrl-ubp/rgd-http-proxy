package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class HeaderTransformConfig extends AbstractTransformConfig {

    @JsonProperty(value = "name")
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}

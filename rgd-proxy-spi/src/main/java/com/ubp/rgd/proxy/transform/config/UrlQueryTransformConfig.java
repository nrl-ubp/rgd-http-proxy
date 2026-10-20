package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class UrlQueryTransformConfig extends AbstractTransformConfig {

    @JsonProperty(value = "name")
    private String name = "";

    @JsonProperty(value = "regexp", defaultValue = "false", required = false)
    private boolean regExp = false;

    public UrlQueryTransformConfig() {
        super();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isRegExp() {
        return regExp;
    }

    public void setRegExp(boolean regExp) {
        this.regExp = regExp;
    }
}

package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class UrlPathTransformConfig extends AbstractTransformConfig {

    @JsonProperty(value = "name-or-position")
    private String nameOrPosition = "";

    @JsonProperty(value = "regexp", defaultValue = "false", required = false)
    private boolean regexp = false;

    public UrlPathTransformConfig() {
        super();
    }

    public String getNameOrPosition() {
        return nameOrPosition;
    }

    public void setNameOrPosition(String nameOrPosition) {
        this.nameOrPosition = nameOrPosition;
    }

    public boolean isRegexp() {
        return regexp;
    }

    public void setRegexp(boolean regexp) {
        this.regexp = regexp;
    }
}

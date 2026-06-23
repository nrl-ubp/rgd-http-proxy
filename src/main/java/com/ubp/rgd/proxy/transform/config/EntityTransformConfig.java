package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class EntityTransformConfig extends AbstractTransformConfig {

    @JsonProperty(value = "json-path")
    private String jsonPath = "";

    public String getJsonPath() {
        return jsonPath;
    }

    public void setJsonPath(String jsonPath) {
        this.jsonPath = jsonPath;
    }
}

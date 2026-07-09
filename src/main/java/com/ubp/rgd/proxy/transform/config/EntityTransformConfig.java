package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public class EntityTransformConfig extends AbstractTransformConfig {

    @JsonProperty(value = "json-path")
    private String jsonPath = "";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty(value = "extract-regex")
    private String extractRegex;

    public String getJsonPath() {
        return jsonPath;
    }

    public void setJsonPath(String jsonPath) {
        this.jsonPath = jsonPath;
    }

    public String getExtractRegex() {
        return extractRegex;
    }

    public void setExtractRegex(String extractRegex) {
        this.extractRegex = extractRegex;
    }
}

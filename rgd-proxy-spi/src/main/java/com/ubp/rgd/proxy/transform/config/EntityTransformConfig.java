package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public class EntityTransformConfig extends AbstractTransformConfig {
    public static final String DATE_REGEXP =
            "^(?:(?<ymd>\\d{4}-\\d{2}-\\d{2})(?:T(?<hms>\\d{2}:\\d{2}:\\d{2}))?|(?<dmyslash>\\d{2}/\\d{2}/\\d{4})|(?<dmycompact>\\d{2}\\d{2}\\d{4})|(?<dmydash>\\d{2}-\\d{2}-\\d{4}))$";

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

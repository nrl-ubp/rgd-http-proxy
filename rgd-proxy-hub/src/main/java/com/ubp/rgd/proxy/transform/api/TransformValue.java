package com.ubp.rgd.proxy.transform.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single value to transform, together with the RPS mapping (class / property) it belongs to and an
 * optional {@code extract-regex}.
 * <p>
 * When {@code extractRegExp} is present and the enclosing set's action is a protection, the regex is
 * used to tokenize the value group by group (word by word), the transformed value keeping the
 * original format. It is ignored for unprotection (tokens are located by their fixed delimiter).
 */
public class TransformValue {

    @JsonProperty(value = "value", required = true)
    private String value;

    @JsonProperty(value = "class-name", required = true)
    private String className;

    @JsonProperty(value = "property-name", required = true)
    private String propertyName;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty(value = "extract-regex")
    private String extractRegExp;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public void setPropertyName(String propertyName) {
        this.propertyName = propertyName;
    }

    public String getExtractRegExp() {
        return extractRegExp;
    }

    public void setExtractRegExp(String extractRegExp) {
        this.extractRegExp = extractRegExp;
    }
}

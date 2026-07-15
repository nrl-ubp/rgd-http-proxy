package com.ubp.rgd.proxy.services.wdx1.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@JsonRootName("wdx1-concat-config")
public class WDX1ConcatConfig {

    @JsonProperty(value="right-context")
    private HashMap<String, String> rightContextEvidences = new HashMap<>();

    @JsonProperty(value="processing-context")
    private Map<String, String> processingContextEvidences = new HashMap<>();

    @JsonProperty(value = "wdx1-concat-rps-mappings")
    private Set<WDX1ConcatRpsMapping> wdx1ConcatRpsMappings = new HashSet<>();

    @JsonProperty(value = "concat-result-class-name")
    private String concatResultClassName;

    @JsonProperty(value = "concat-result-property-name")
    private String concatResultPropertyName;

    @JsonProperty(value = "date-format", defaultValue = "yyyy-MM-dd")
    private String dateFormat;

    public HashMap<String, String> getRightContextEvidences() {
        return rightContextEvidences;
    }

    public void setRightContextEvidences(HashMap<String, String> rightContextEvidences) {
        this.rightContextEvidences = rightContextEvidences;
    }

    public Map<String, String> getProcessingContextEvidences() {
        return processingContextEvidences;
    }

    public void setProcessingContextEvidences(Map<String, String> processingContextEvidences) {
        this.processingContextEvidences = processingContextEvidences;
    }

    public Set<WDX1ConcatRpsMapping> getWdx1ConcatRpsMappings() {
        return wdx1ConcatRpsMappings;
    }

    public void setWdx1ConcatRpsMappings(Set<WDX1ConcatRpsMapping> wdx1ConcatRpsMappings) {
        this.wdx1ConcatRpsMappings = wdx1ConcatRpsMappings;
    }

    public String getConcatResultPropertyName() {
        return concatResultPropertyName;
    }

    public void setConcatResultPropertyName(String concatResultPropertyName) {
        this.concatResultPropertyName = concatResultPropertyName;
    }

    public String getConcatResultClassName() {
        return concatResultClassName;
    }

    public void setConcatResultClassName(String concatResultClassName) {
        this.concatResultClassName = concatResultClassName;
    }

    public String getDateFormat() {
        return dateFormat;
    }

    public void setDateFormat(String dateFormat) {
        this.dateFormat = dateFormat;
    }
}

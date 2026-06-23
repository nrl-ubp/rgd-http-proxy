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
}

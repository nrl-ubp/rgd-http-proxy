package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

import java.util.*;

@JsonRootName("EndPointTransformConfig")
public class EndPointTransformConfig {

    @JsonProperty(value="endpoint-path")
    private String endpointPath = "";

    @JsonProperty(value="endpoint-methods")
    private List<String> endpointMethods;

    @JsonProperty(value="endpoint-transform-when")
    private String endpointTransformWhen = "AFTER";

    @JsonProperty(value="right-context")
    private HashMap<String, String> rightContextEvidences = new HashMap<>();

    @JsonProperty(value="processing-context")
    private Map<String, String> processingContextEvidences = new HashMap<>();

    @JsonProperty(value="header-transform-configs")
    private Set<HeaderTransformConfig> headerTransformConfigs = new HashSet<>();

    @JsonProperty(value = "entity-transform-configs")
    private Set<EntityTransformConfig> entityTransformConfigs = new HashSet<>();

    @JsonProperty(value = "url-query-transform-configs")
    private Set<UrlQueryTransformConfig> queryTransformConfigs = new HashSet<>();

    @JsonProperty(value = "url-path-transform-configs")
    private Set<UrlPathTransformConfig> pathTransformConfigs = new HashSet<>();

    public Set<HeaderTransformConfig> getHeaderTransformConfigs() {
        return headerTransformConfigs;
    }

    public Set<EntityTransformConfig> getAttributeTransformConfigs() {
        return entityTransformConfigs;
    }

    public String getEndpointPath() {
        return endpointPath;
    }

    public void setEndpointPath(String endpointPath) {
        this.endpointPath = endpointPath;
    }

    public List<String> getEndpointMethods() {
        return endpointMethods;
    }

    public void setEndpointMethods(List<String> endpointMethods) {
        this.endpointMethods = endpointMethods;
    }

    public String getEndpointTransformWhen() {
        return endpointTransformWhen;
    }

    public void setEndpointTransformWhen(String endpointTransformWhen) {
        this.endpointTransformWhen = endpointTransformWhen;
    }

    public Map<String, HeaderTransformConfig> sortHeaderTransformConfig() {
        Map<String, HeaderTransformConfig> results = new HashMap<>();
        headerTransformConfigs.forEach(cfg -> results.put(cfg.getName(), cfg));
        return results;
    }

    public Map<String, EntityTransformConfig> sortAttributeTransformsConfig() {
        Map<String, EntityTransformConfig> results = new HashMap<>();
        entityTransformConfigs.forEach(cfg -> results.put(cfg.getJsonPath(), cfg));
        return results;
    }

    public Map<String, UrlQueryTransformConfig> sortQueryTransformConfigs() {
        Map<String, UrlQueryTransformConfig> results = new HashMap<>();
        queryTransformConfigs.forEach(cfg -> results.put(cfg.getName(), cfg));
        return results;
    }

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

    public void setHeaderTransformConfigs(Set<HeaderTransformConfig> headerTransformConfigs) {
        this.headerTransformConfigs = headerTransformConfigs;
    }

    public Set<EntityTransformConfig> getEntityTransformConfigs() {
        return entityTransformConfigs;
    }

    public void setEntityTransformConfigs(Set<EntityTransformConfig> entityTransformConfigs) {
        this.entityTransformConfigs = entityTransformConfigs;
    }

    public Set<UrlQueryTransformConfig> getQueryTransformConfigs() {
        return queryTransformConfigs;
    }

    public void setQueryTransformConfigs(Set<UrlQueryTransformConfig> queryTransformConfigs) {
        this.queryTransformConfigs = queryTransformConfigs;
    }

    public Set<UrlPathTransformConfig> getPathTransformConfigs() {
        return pathTransformConfigs;
    }

    public void setPathTransformConfigs(Set<UrlPathTransformConfig> pathTransformConfigs) {
        this.pathTransformConfigs = pathTransformConfigs;
    }
}

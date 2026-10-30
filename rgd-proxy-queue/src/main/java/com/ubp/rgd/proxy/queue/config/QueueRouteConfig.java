package com.ubp.rgd.proxy.queue.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One route of {@code mq_transform_config.json}: the messages of the input queue are transformed and
 * sent to the output queue, those that cannot be transformed to the error queue.
 */
@RegisterForReflection
public class QueueRouteConfig {

    @JsonProperty("name")
    private String name;

    /** Consumers of the input queue. 1 keeps the order of the messages. */
    @JsonProperty("concurrency")
    private int concurrency = 1;

    @JsonProperty("input")
    private QueueEndpointConfig input;

    @JsonProperty("output")
    private QueueEndpointConfig output;

    /** Where the messages that cannot be transformed go; without it, they are rolled back. */
    @JsonProperty("error")
    private QueueEndpointConfig error;

    @JsonProperty("right-context")
    private Map<String, String> rightContextEvidences = new HashMap<>();

    @JsonProperty("processing-context")
    private Map<String, String> processingContextEvidences = new HashMap<>();

    @JsonProperty("entity-transform-configs")
    private List<EntityTransformConfig> entityTransformConfigs = new ArrayList<>();

    /** @return the entity transformations keyed by JSON path, as {@code EndPointTransformer.transformJson} takes them */
    public Map<String, EntityTransformConfig> sortAttributeTransformsConfig() {
        Map<String, EntityTransformConfig> results = new HashMap<>();
        entityTransformConfigs.forEach(cfg -> results.put(cfg.getJsonPath(), cfg));
        return results;
    }

    /** @return whether the route unprotects, read from the {@code Action} processing context evidence */
    public boolean unprotects() {
        return processingContextEvidences != null && processingContextEvidences.entrySet().stream()
                .anyMatch(e -> "Action".equalsIgnoreCase(e.getKey()) && "Unprotect".equalsIgnoreCase(e.getValue()));
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public QueueEndpointConfig getInput() {
        return input;
    }

    public void setInput(QueueEndpointConfig input) {
        this.input = input;
    }

    public QueueEndpointConfig getOutput() {
        return output;
    }

    public void setOutput(QueueEndpointConfig output) {
        this.output = output;
    }

    public QueueEndpointConfig getError() {
        return error;
    }

    public void setError(QueueEndpointConfig error) {
        this.error = error;
    }

    public Map<String, String> getRightContextEvidences() {
        return rightContextEvidences;
    }

    public void setRightContextEvidences(Map<String, String> rightContextEvidences) {
        this.rightContextEvidences = rightContextEvidences;
    }

    public Map<String, String> getProcessingContextEvidences() {
        return processingContextEvidences;
    }

    public void setProcessingContextEvidences(Map<String, String> processingContextEvidences) {
        this.processingContextEvidences = processingContextEvidences;
    }

    public List<EntityTransformConfig> getEntityTransformConfigs() {
        return entityTransformConfigs;
    }

    public void setEntityTransformConfigs(List<EntityTransformConfig> entityTransformConfigs) {
        this.entityTransformConfigs = entityTransformConfigs;
    }
}

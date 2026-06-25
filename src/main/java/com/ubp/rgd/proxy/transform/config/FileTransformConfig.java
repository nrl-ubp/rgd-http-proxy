package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FileTransformConfig {

    @JsonProperty(value = "name")
    private String name;

    @JsonProperty(value = "source-directory")
    private String sourceDirectory;

    @JsonProperty(value = "target-directory")
    private String targetDirectory;

    @JsonProperty(value = "error-directory")
    private String errorDirectory;

    @JsonProperty(value = "work-in-progress-suffix")
    private String workInProgressSuffix = ".processing";

    @JsonProperty(value = "file-pattern")
    private String filePattern = ".*\\.json$";

    @JsonProperty(value = "preserve-directory-structure")
    private boolean preserveDirectoryStructure = true;

    @JsonProperty(value = "right-context")
    private HashMap<String, String> rightContextEvidences = new HashMap<>();

    @JsonProperty(value = "processing-context")
    private Map<String, String> processingContextEvidences = new HashMap<>();

    @JsonProperty(value = "entity-transform-configs")
    private Set<EntityTransformConfig> entityTransformConfigs = new HashSet<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceDirectory() {
        return sourceDirectory;
    }

    public void setSourceDirectory(String sourceDirectory) {
        this.sourceDirectory = sourceDirectory;
    }

    public String getTargetDirectory() {
        return targetDirectory;
    }

    public void setTargetDirectory(String targetDirectory) {
        this.targetDirectory = targetDirectory;
    }

    public String getErrorDirectory() {
        return errorDirectory;
    }

    public void setErrorDirectory(String errorDirectory) {
        this.errorDirectory = errorDirectory;
    }

    public String getWorkInProgressSuffix() {
        return workInProgressSuffix;
    }

    public void setWorkInProgressSuffix(String workInProgressSuffix) {
        this.workInProgressSuffix = workInProgressSuffix;
    }

    public String getFilePattern() {
        return filePattern;
    }

    public void setFilePattern(String filePattern) {
        this.filePattern = filePattern;
    }

    public boolean isPreserveDirectoryStructure() {
        return preserveDirectoryStructure;
    }

    public void setPreserveDirectoryStructure(boolean preserveDirectoryStructure) {
        this.preserveDirectoryStructure = preserveDirectoryStructure;
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

    public Set<EntityTransformConfig> getEntityTransformConfigs() {
        return entityTransformConfigs;
    }

    public void setEntityTransformConfigs(Set<EntityTransformConfig> entityTransformConfigs) {
        this.entityTransformConfigs = entityTransformConfigs;
    }

    public Map<String, EntityTransformConfig> sortAttributeTransformsConfig() {
        Map<String, EntityTransformConfig> results = new HashMap<>();
        entityTransformConfigs.forEach(cfg -> results.put(cfg.getJsonPath(), cfg));
        return results;
    }
}

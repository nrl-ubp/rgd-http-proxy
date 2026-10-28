package com.ubp.rgd.proxy.transform.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * A set of values sharing the same RPS transformation context: an {@code action} (e.g. Protect /
 * Unprotect), a {@code target} (e.g. WDX1) and a {@code jurisdiction} code (CH, LU, MC, ...).
 * All values of a set are transformed in a single RPS engine call.
 * <p>
 * The {@code jurisdiction} selects the RPS secrets manager of the set through the secrets manager
 * mapping file. It is optional: a set without one uses the file's default mapping.
 */
public class TransformSet {

    @JsonProperty(value = "action", required = true)
    private String action;

    @JsonProperty(value = "target", required = true)
    private String target;

    @JsonProperty(value = "module", required = true)
    private String module;

    @JsonProperty(value = "jurisdiction")
    private String jurisdiction;

    @JsonProperty(value = "values", required = true)
    private List<TransformValue> values = new ArrayList<>();

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getJurisdiction() {
        return jurisdiction;
    }

    public void setJurisdiction(String jurisdiction) {
        this.jurisdiction = jurisdiction;
    }

    public List<TransformValue> getValues() {
        return values;
    }

    public void setValues(List<TransformValue> values) {
        this.values = values;
    }
}

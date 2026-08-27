package com.ubp.rgd.proxy.transform.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * The transformed values of a single {@link TransformSet}, in the same order as the request values.
 */
public class TransformResultSet {

    @JsonProperty(value = "values")
    private List<String> values = new ArrayList<>();

    public TransformResultSet() {
    }

    public TransformResultSet(List<String> values) {
        this.values = values;
    }

    public List<String> getValues() {
        return values;
    }

    public void setValues(List<String> values) {
        this.values = values;
    }
}

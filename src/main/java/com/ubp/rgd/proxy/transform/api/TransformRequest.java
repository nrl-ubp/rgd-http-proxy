package com.ubp.rgd.proxy.transform.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

import java.util.ArrayList;
import java.util.List;

/**
 * Request payload of the {@code /transform} endpoint: a list of {@link TransformSet}, each grouping
 * values sharing the same action / target / jurisdiction.
 */
@JsonRootName("transformRequest")
public class TransformRequest {

    @JsonProperty(value = "sets", required = true)
    private List<TransformSet> sets = new ArrayList<>();

    public List<TransformSet> getSets() {
        return sets;
    }

    public void setSets(List<TransformSet> sets) {
        this.sets = sets;
    }
}

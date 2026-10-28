package com.ubp.rgd.proxy.transform.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

import java.util.ArrayList;
import java.util.List;

/**
 * Response payload of the {@code /transform} endpoint: the transformed values grouped per set, in
 * the same order as the request.
 */
@JsonRootName("transformResponse")
public class TransformResponse {

    @JsonProperty(value = "results")
    private List<TransformResultSet> results = new ArrayList<>();

    public List<TransformResultSet> getResults() {
        return results;
    }

    public void setResults(List<TransformResultSet> results) {
        this.results = results;
    }
}

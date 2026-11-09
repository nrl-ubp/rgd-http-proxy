package com.ubp.rgd.proxy.fuzzysearch.dto;

import java.util.List;

/**
 * Response body for the {@code GET /phonetic/neighbours} endpoint.
 *
 * <p>Example:
 * <pre>
 * {
 *   "term":            "Nicola",
 *   "topN":            5,
 *   "totalNeighbours": 12,
 *   "neighbours":      ["nicolas", "nichola", "nicholas", "nicolo", ...]
 * }
 * </pre>
 */
public record PhoneticNeighboursResponse(
        /** The input term as provided by the caller. */
        String term,

        /** Number of nearest KMeans clusters that were explored. */
        int topN,

        /** Total number of distinct tokens found across the N nearest clusters. */
        int totalNeighbours,

        /** Ordered list of training tokens whose cluster is within the N nearest centroids. */
        List<String> neighbours
) {}

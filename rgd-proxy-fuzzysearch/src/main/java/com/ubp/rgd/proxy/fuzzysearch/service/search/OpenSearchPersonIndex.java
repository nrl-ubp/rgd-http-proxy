package com.ubp.rgd.proxy.fuzzysearch.service.search;

import java.util.List;

public interface OpenSearchPersonIndex {
    void index(PersonSearchDocument doc);

    /**
     * Deletes every document of a tenant, before a clean reindex.
     *
     * @param tenantId the tenant
     * @return the number of deleted documents
     * @throws IllegalStateException when the deletion fails
     */
    long deleteTenant(String tenantId);

    /** Makes the documents indexed so far visible to the searches. */
    void refresh();

    /**
     * Searches the index for persons whose {@code phonetic} field contains
     * ANY of the given signatures AND whose {@code tenantId} matches.
     *
     * <p>Results are returned in the natural OpenSearch score order
     * ({@code _score} descending).
     *
     * @param tenantId  tenant filter (strict keyword match)
     * @param phonetic  SHA-256 hashes to search for (OR semantics)
     * @return ordered list of person IDs
     */
    List<Long> searchByPhonetic(String tenantId, List<String> phonetic);

    /**
     * Boosted phonetic search with positional scoring.
     *
     * <p>Sends a single {@code bool/should} query with four {@code terms}
     * clauses at different boost levels:
     * <ol>
     *   <li>{@code phonetic_prefix} with {@code boost = 4.0} → "starts with"</li>
     *   <li>{@code phonetic_suffix} with {@code boost = 3.0} → "ends with"</li>
     *   <li>{@code phonetic} (query hashes) with {@code boost = 2.0} → "contains"</li>
     *   <li>{@code phonetic} (neighbour hashes) with {@code boost = 1.0} → fuzzy recall</li>
     * </ol>
     *
     * <p>OpenSearch {@code _score} descending order yields: starts-with first,
     * ends-with second, contains third, other fuzzy matches last.
     *
     * @param tenantId         tenant filter
     * @param prefixSigs       hashes to match against {@code phonetic_prefix} (boost 4)
     * @param suffixSigs       hashes to match against {@code phonetic_suffix} (boost 3)
     * @param containsSigs     hashes to match against {@code phonetic} (boost 2)
     * @param neighbourSigs    hashes to match against {@code phonetic} (boost 1)
     * @return ordered list of person IDs (best match first)
     */
    List<Long> searchByPhoneticBoosted(String tenantId,
                                       List<String> prefixSigs,
                                       List<String> suffixSigs,
                                       List<String> containsSigs,
                                       List<String> neighbourSigs);
}

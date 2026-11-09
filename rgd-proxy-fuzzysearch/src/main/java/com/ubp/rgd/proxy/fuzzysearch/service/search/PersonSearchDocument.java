package com.ubp.rgd.proxy.fuzzysearch.service.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Document stored in OpenSearch for phonetic person search.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code docId} – unique identifier ({@code "person-<id>"}).</li>
 *   <li>{@code tenantId} – tenant scope filter.</li>
 *   <li>{@code phonetic} – SHA-256 hashes of ALL 3-gram and 4-gram
 *       character n-grams of each name token.</li>
 *   <li>{@code phonetic_prefix} – SHA-256 hashes of n-grams starting at
 *       position 0 (prefix n-grams).  Used for "starts-with" boosting.</li>
 *   <li>{@code phonetic_suffix} – SHA-256 hashes of n-grams ending at
 *       the last character (suffix n-grams).  Used for "ends-with" boosting.</li>
 * </ul>
 */
public class PersonSearchDocument {
    public String docId;          // ex: "person-123"
    public String tenantId;
    public List<String> phonetic; // ALL n-gram hashes

    @JsonProperty("phonetic_prefix")
    public List<String> phoneticPrefix; // prefix n-gram hashes

    @JsonProperty("phonetic_suffix")
    public List<String> phoneticSuffix; // suffix n-gram hashes
}

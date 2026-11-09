package com.ubp.rgd.proxy.fuzzysearch.service.phonetic;

import java.util.List;

/**
 * Produces privacy-preserving phonetic/semantic signatures for name tokens.
 *
 * <h2>Single signature (index time)</h2>
 * {@link #signature(String)} returns the hash of the single nearest KMeans
 * cluster. Use it when indexing a person document.
 *
 * <h2>Neighbourhood signatures (search time)</h2>
 * {@link #neighbourSignatures(String)} returns the hashes of the N nearest
 * KMeans clusters, where N is controlled by the
 * {@code phonetic.kmeans.search-neighbours} configuration property (default 5).
 * Passing all N hashes to a {@code terms} query in OpenSearch gives fuzzy
 * semantic matching: "Nicola" will match "Nicolas", "Nicholas", "Nichola", etc.
 * because their FastText embeddings are geometrically close and therefore share
 * neighbouring cluster centroids.
 */
public interface PhoneticSignatureService {

    /**
     * Returns the SHA-256 hash of the single nearest training token.
     * Kept for backward compatibility; prefer {@link #indexSignatures(String)}
     * for new indexing code.
     *
     * @param token a single name token (e.g. "Mohamed")
     * @return SHA-256 hex string
     */
    String signature(String token);

    /**
     * Returns the full set of privacy-preserving signatures to store in the
     * index document for the given token.
     *
     * <p>The set is built from <em>character n-grams</em> (trigrams and
     * 4-grams) of the normalised token. Storing multiple n-gram hashes per
     * token is what makes fuzzy prefix/substring search possible:
     * <ul>
     *   <li>"nicolas" → hashes of {"nic","ico","col","ola","las","nico","icol","cola","olas"}</li>
     *   <li>"nicole"  → hashes of {"nic","ico","col","ole","nico","icol","cole"}</li>
     * </ul>
     * A query for "Nico" will generate n-grams {"nic","ico","nico"} and the
     * resulting hashes will match both "nicolas" and "nicole" via the
     * OpenSearch {@code terms} query (OR semantics).
     *
     * @param token a single name token (e.g. "Nicolas")
     * @return ordered list of SHA-256 hex strings to store in the index
     */
    List<String> indexSignatures(String token);

    /**
     * Returns the SHA-256 hashes of the <strong>prefix</strong> n-grams
     * (3-grams and 4-grams that start at position 0) of the normalised token.
     *
     * <p>Example: {@code "nicolas"} → prefix n-grams {@code {"nic", "nico"}}
     * → 2 SHA-256 hashes.
     *
     * <p>Stored in the {@code phonetic_prefix} OpenSearch field and used at
     * search time to boost documents whose names <em>start with</em> the query.
     *
     * @param token a single name token
     * @return SHA-256 hex strings of prefix n-grams
     */
    List<String> indexPrefixSignatures(String token);

    /**
     * Returns the SHA-256 hashes of the <strong>suffix</strong> n-grams
     * (3-grams and 4-grams that end at the last character) of the normalised token.
     *
     * <p>Example: {@code "nicolas"} → suffix n-grams {@code {"las", "olas"}}
     * → 2 SHA-256 hashes.
     *
     * <p>Stored in the {@code phonetic_suffix} OpenSearch field and used at
     * search time to boost documents whose names <em>end with</em> the query.
     *
     * @param token a single name token
     * @return SHA-256 hex strings of suffix n-grams
     */
    List<String> indexSuffixSignatures(String token);

    /**
     * Returns the SHA-256 hashes of the top-K most similar training tokens.
     * Used at search time (current: same as {@link #indexSignatures}).
     *
     * @param token a single name token (e.g. "Nicola")
     * @return ordered list of SHA-256 hex strings (nearest first)
     */
    List<String> neighbourSignatures(String token);

    /**
     * Returns the top-K most similar training tokens in <em>clear text</em>,
     * ordered by hybrid score descending (cosine + Jaro-Winkler + boosts).
     *
     * <p>Used at search time to expand a query token to its nearest training
     * neighbours so that each neighbour's {@link #indexSignatures} can be
     * computed and used as a secondary (lower-boosted) query tier.
     *
     * @param token a single query token (e.g. "Nico")
     * @return ordered list of training token strings
     */
    List<String> neighbourTokens(String token);
}

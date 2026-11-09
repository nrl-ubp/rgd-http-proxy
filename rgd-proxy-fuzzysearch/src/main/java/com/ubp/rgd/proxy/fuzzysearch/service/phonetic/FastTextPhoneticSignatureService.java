package com.ubp.rgd.proxy.fuzzysearch.service.phonetic;

import com.github.jfasttext.JFastText;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;

/**
 * Phonetic signature service using a <strong>hybrid search</strong> strategy
 * that is a Java port of the Python {@code search()} function in
 * {@code src/main/python/test-fasttext.py}.
 *
 * <h2>Search algorithm (per query token)</h2>
 * <ol>
 *   <li><strong>Cosine similarity</strong> – L2-normalised FastText embedding
 *       of the query vs. every training token (inner-product after normalisation
 *       ≡ cosine similarity, matching Python's {@code faiss.IndexFlatIP}).</li>
 *   <li><strong>Jaro-Winkler</strong> string similarity (weight {@code beta})
 *       for every training token.</li>
 *   <li><strong>Prefix boost</strong> (weight {@code gamma}): full-token or
 *       word-level prefix match.</li>
 *   <li><strong>Suffix boost</strong> (weight {@code delta}): full-token or
 *       word-level suffix match.</li>
 *   <li><strong>Substring boost</strong> (weight {@code epsilon}): query is a
 *       substring of the training token.</li>
 * </ol>
 * Combined scores are summed per training token; the top-{@code topK} tokens
 * are selected as the signature set.
 *
 * <h2>Index-time ({@link #indexSignatures})</h2>
 * {@code hybridSearch(token, topK)} → top-K training token names →
 * SHA-256 hash of each → stored in OpenSearch {@code phonetic} field.
 *
 * <h2>Search-time ({@link #neighbourSignatures})</h2>
 * Same algorithm on the query term → same hashes → {@code terms} query (OR)
 * in OpenSearch finds documents whose index-time signatures overlap.
 *
 * <h2>Vocabulary</h2>
 * The training vocabulary is held <strong>in memory only</strong>: it is built
 * from the clear name tokens handed to {@link #rebuildVocabulary(Collection)},
 * which the {@code TrainingDataService} unprotects from the {@code PERSON}
 * table. Nothing is written to disk. A rebuild swaps the vocabulary atomically,
 * so concurrent searches always see a consistent one. Until a vocabulary is
 * available the neighbour lookups return nothing.
 *
 * <h2>Configuration (application.properties)</h2>
 * <pre>
 *   fuzzy-search.phonetic.fasttext.model-path=models/cc.en.300.bin
 *   fuzzy-search.phonetic.search.top-k=10
 *   fuzzy-search.phonetic.search.beta=2.2
 *   fuzzy-search.phonetic.search.gamma=1.0
 *   fuzzy-search.phonetic.search.delta=0.8
 *   fuzzy-search.phonetic.search.epsilon=0.6
 *   fuzzy-search.phonetic.signature.salt=secret-salt
 * </pre>
 */
@ApplicationScoped
public class FastTextPhoneticSignatureService implements PhoneticSignatureService {

    private static final Logger LOG = LoggerFactory.getLogger(FastTextPhoneticSignatureService.class);

    /** Reusable Jaro-Winkler calculator (stateless, thread-safe). */
    private static final JaroWinklerSimilarity JW = new JaroWinklerSimilarity();

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    /** Path to the FastText binary model (e.g. cc.en.300.bin). */
    @ConfigProperty(name = "fuzzy-search.phonetic.fasttext.model-path", defaultValue = "models/cc.en.300.bin")
    String fastTextModelPath;

    /** Number of top results returned by the hybrid search (= number of hashes per token). */
    @ConfigProperty(name = "fuzzy-search.phonetic.search.top-k", defaultValue = "10")
    int topK;

    /**
     * Weight of the Jaro-Winkler string similarity component.
     * Python default: 0.3; the example call uses 2.2 to emphasise lexical
     * similarity over semantic similarity.
     */
    @ConfigProperty(name = "fuzzy-search.phonetic.search.beta", defaultValue = "2.2")
    double beta;

    /** Weight of the prefix boost. */
    @ConfigProperty(name = "fuzzy-search.phonetic.search.gamma", defaultValue = "1.0")
    double gamma;

    /** Weight of the suffix boost. */
    @ConfigProperty(name = "fuzzy-search.phonetic.search.delta", defaultValue = "0.8")
    double delta;

    /** Weight of the substring boost. */
    @ConfigProperty(name = "fuzzy-search.phonetic.search.epsilon", defaultValue = "0.6")
    double epsilon;

    /** Secret salt prepended to the training token before hashing. */
    @ConfigProperty(name = "fuzzy-search.phonetic.signature.salt", defaultValue = "secret-salt")
    String saltString;

    // -----------------------------------------------------------------------
    // Runtime state
    // -----------------------------------------------------------------------

    private JFastText fastText;
    private byte[] salt;

    /**
     * The training vocabulary: a flat index of <em>L2-normalised</em> token
     * embeddings and the tokens at the same positions. Inner-product search on
     * unit vectors ≡ cosine similarity (mirrors Python's {@code faiss.IndexFlatIP}
     * after {@code faiss.normalize_L2}).
     *
     * @param index  the embeddings
     * @param tokens {@code tokens[i]} is the token at position {@code i}
     */
    private record Vocabulary(FlatL2Index index, String[] tokens) {
    }

    /** Swapped as a whole by {@link #rebuildVocabulary}, {@code null} until the first one. */
    private volatile Vocabulary vocabulary;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @PostConstruct
    void init() {
        salt = saltString.getBytes(StandardCharsets.UTF_8);

        // Load FastText
        if (!java.nio.file.Files.isRegularFile(java.nio.file.Path.of(fastTextModelPath))) {
            throw new IllegalStateException("The FastText model of the fuzzy search is missing: "
                    + java.nio.file.Path.of(fastTextModelPath).toAbsolutePath()
                    + ". Download cc.en.300.bin there or set fuzzy-search.phonetic.fasttext.model-path.");
        }
        LOG.info("Loading FastText model from: " + fastTextModelPath);
        fastText = new JFastText();
        fastText.loadModel(fastTextModelPath);
        LOG.info("FastText model loaded.");

        LOG.info("The training vocabulary is built in memory from the PERSON table, see TrainingDataService.");
    }

    // -----------------------------------------------------------------------
    // PhoneticSignatureService – single signature (backward compatibility)
    // -----------------------------------------------------------------------

    @Override
    public String signature(String token) {
        List<String> top = hybridSearch(normalize(token), 1);
        return top.isEmpty() ? sha256Hex(salt, normalize(token))
                             : sha256Hex(salt, top.get(0));
    }

    // -----------------------------------------------------------------------
    // PhoneticSignatureService – multi-hash index time (n-gram based)
    // -----------------------------------------------------------------------

    /**
     * Returns the SHA-256 hashes of all <strong>character 3-grams and
     * 4-grams</strong> of the normalised token.
     *
     * <p>This is the canonical signature set stored in OpenSearch at index
     * time AND computed at search time for each neighbour token:
     * <ul>
     *   <li>"nicolas" → n-grams {nic,ico,col,ola,las,nico,icol,cola,olas}
     *                 → 9 SHA-256 hashes</li>
     *   <li>"nicola"  → n-grams {nic,ico,col,ola,nico,icol,cola}
     *                 → 7 SHA-256 hashes</li>
     * </ul>
     * A search for the neighbour "nicolas" will exactly match persons who
     * were indexed with the name "nicolas" because they share the same
     * n-gram hashes.  Overlap with adjacent neighbours ("nicola", "nicole")
     * further broadens recall while staying precise within each neighbour tier.
     *
     * <p><strong>Note:</strong> changing this method invalidates any existing
     * OpenSearch documents.  Run {@code POST /admin/reindex} after deploying.
     *
     * @param token a single name token (e.g. "Nicolas")
     * @return ordered list of unique SHA-256 hex strings (3-gram hashes, then 4-gram hashes)
     */
    @Override
    public List<String> indexSignatures(String token) {
        return ngramHashes(normalize(token), 3, 4);
    }

    @Override
    public List<String> indexPrefixSignatures(String token) {
        return positionalNgramHashes(normalize(token), 3, 4, true);
    }

    @Override
    public List<String> indexSuffixSignatures(String token) {
        return positionalNgramHashes(normalize(token), 3, 4, false);
    }

    /**
     * Computes SHA-256 hashes of n-grams at a specific position (prefix or suffix).
     *
     * @param s      normalised (lowercase) input string
     * @param minN   minimum n-gram length (inclusive)
     * @param maxN   maximum n-gram length (inclusive)
     * @param prefix {@code true} = n-grams starting at position 0;
     *               {@code false} = n-grams ending at the last position
     * @return list of SHA-256 hex strings
     */
    private List<String> positionalNgramHashes(String s, int minN, int maxN, boolean prefix) {
        java.util.LinkedHashSet<String> ngrams = new java.util.LinkedHashSet<>();
        for (int n = minN; n <= maxN; n++) {
            if (n > s.length()) continue;
            if (prefix) {
                ngrams.add(s.substring(0, n));              // starts at pos 0
            } else {
                ngrams.add(s.substring(s.length() - n));    // ends at last pos
            }
        }
        List<String> hashes = new ArrayList<>(ngrams.size());
        for (String ng : ngrams) {
            hashes.add(sha256Hex(salt, ng));
        }
        return hashes;
    }

    /**
     * Computes SHA-256 hashes of all unique character n-grams of {@code s}
     * in the range [{@code minN}, {@code maxN}].
     *
     * <p>A {@link java.util.LinkedHashSet} is used internally so that
     * duplicate n-grams (e.g. "aaa" in "aaab") are counted only once, and
     * the extraction order (left-to-right, shortest-to-longest) is preserved.
     *
     * <p>If {@code s} is shorter than {@code minN}, an empty list is returned.
     *
     * @param s    normalised (lowercase) input string
     * @param minN minimum n-gram length (inclusive)
     * @param maxN maximum n-gram length (inclusive)
     * @return list of SHA-256 hex strings, one per unique n-gram
     */
    private List<String> ngramHashes(String s, int minN, int maxN) {
        java.util.LinkedHashSet<String> ngrams = new java.util.LinkedHashSet<>();
        for (int n = minN; n <= maxN; n++) {
            for (int i = 0; i <= s.length() - n; i++) {
                ngrams.add(s.substring(i, i + n));
            }
        }
        List<String> hashes = new ArrayList<>(ngrams.size());
        for (String ng : ngrams) {
            hashes.add(sha256Hex(salt, ng));
        }
        return hashes;
    }

    // -----------------------------------------------------------------------
    // PhoneticSignatureService – search time
    // -----------------------------------------------------------------------

    /**
     * Returns the SHA-256 hashes of the top-{@code fuzzy-search.phonetic.search.top-k}
     * training tokens found by the hybrid search for this query token.
     *
     * <p>Because both {@link #indexSignatures} and this method use the same
     * hybrid search algorithm, any training token that appears in the top-K
     * for a query will also appear in the top-K for similar indexed names →
     * the OpenSearch {@code terms} (OR) query finds those persons.
     */
    @Override
    public List<String> neighbourSignatures(String token) {
        return toHashes(neighbourTokens(token));
    }

    // -----------------------------------------------------------------------
    // Clear-text neighbours – interface + debug overload
    // -----------------------------------------------------------------------

    /**
     * Returns the top-{@code fuzzy-search.phonetic.search.top-k} training tokens for
     * the query in clear text, ordered by hybrid score descending.
     *
     * <p>Used at search time to expand a query token to its nearest training
     * neighbours so that each neighbour's {@link #indexSignatures} can be
     * computed and used as a secondary (lower-boosted) query tier.
     */
    @Override
    public List<String> neighbourTokens(String token) {
        return neighbourTokens(token, topK);
    }

    /**
     * Explicit-count variant of {@link #neighbourTokens(String)}.
     * Used by the {@code GET /phonetic/neighbours} debug endpoint.
     */
    public List<String> neighbourTokens(String token, int topN) {
        return hybridSearch(normalize(token), topN);
    }

    // -----------------------------------------------------------------------
    // Hybrid search – Java port of test-fasttext.py search()
    // -----------------------------------------------------------------------

    /**
     * Hybrid search over the training vocabulary.
     *
     * <p>This is a direct Java port of the Python {@code search()} function:
     * <pre>
     * score[i] = cosine_similarity(q_vec, train_vec[i])   // FAISS inner product
     *          + beta    * jaro_winkler(q, train[i])
     *          + gamma   * prefix_boost(q, train[i])       // 1.0 or 0.7 (word-level)
     *          + delta   * suffix_boost(q, train[i])       // 1.0 or 0.7 (word-level)
     *          + epsilon * substring_boost(q, train[i])
     * </pre>
     *
     * @param q    normalised (lowercase) query token
     * @param topN number of results to return
     * @return list of training token names, ordered by score descending; empty
     *         while no vocabulary is available
     */
    public List<String> hybridSearch(String q, int topN) {
        // Read once: a concurrent rebuild must not mix two vocabularies
        Vocabulary current = vocabulary;
        if (current == null || current.tokens().length == 0) {
            LOG.debug("No training vocabulary yet, no neighbour for '" + q + "'.");
            return new ArrayList<>();
        }
        String[] trainingTokens = current.tokens();

        float[] qVec = getEmbeddingNormalized(q);
        float[] rawVectors = current.index().getVectors();
        int dim  = current.index().dimension();
        int n    = current.index().ntotal();

        double[] combined = new double[n];

        // ── Cosine similarity (inner product on L2-normalised unit vectors) ──────
        // In Python: alpha * sem_score + (1-alpha) * lex_score
        // Since sem_vectors == lex_vectors, this simplifies to just the IP score.
        for (int i = 0; i < n; i++) {
            combined[i] += innerProduct(qVec, rawVectors, i, dim);
        }

        // ── Jaro-Winkler + boosts ────────────────────────────────────────────────
        for (int i = 0; i < n; i++) {
            String nameLower = trainingTokens[i]; // already normalised at build time

            // Jaro-Winkler string similarity
            combined[i] += beta * JW.apply(q, nameLower);

            // Prefix boost
            if (nameLower.startsWith(q)) {
                combined[i] += gamma;
            } else {
                for (String tok : nameLower.split(" ")) {
                    if (tok.startsWith(q)) {
                        combined[i] += gamma * 0.7;
                        break;
                    }
                }
            }

            // Suffix boost
            if (nameLower.endsWith(q)) {
                combined[i] += delta;
            } else {
                for (String tok : nameLower.split(" ")) {
                    if (tok.endsWith(q)) {
                        combined[i] += delta * 0.7;
                        break;
                    }
                }
            }

            // Substring boost
            if (nameLower.contains(q)) {
                combined[i] += epsilon;
            }
        }

        // ── Sort descending and return top-N ─────────────────────────────────────
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Double.compare(combined[b], combined[a]));

        int actualTop = Math.min(topN, n);
        List<String> result = new ArrayList<>(actualTop);
        for (int i = 0; i < actualTop; i++) {
            result.add(trainingTokens[indices[i]]);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Index build & persist
    // -----------------------------------------------------------------------

    /**
     * Replaces the training vocabulary by the given clear tokens.
     *
     * <p>The tokens are normalised, deduplicated and sorted, so the same input
     * always gives the same vocabulary. Their <em>L2-normalised</em> FastText
     * embeddings are computed and the vocabulary is swapped atomically: the
     * searches running meanwhile keep using the previous one. An empty
     * collection clears the vocabulary.
     *
     * @param tokens the clear name tokens
     * @return the number of tokens of the new vocabulary
     */
    public int rebuildVocabulary(Collection<String> tokens) {
        String[] sorted = tokens.stream()
                .filter(Objects::nonNull)
                .map(this::normalize)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new))
                .toArray(String[]::new);
        if (sorted.length == 0) {
            vocabulary = null;
            LOG.warn("Empty training vocabulary: the fuzzy search runs without neighbours.");
            return 0;
        }

        LOG.info("Computing L2-normalised FastText embeddings for " + sorted.length + " tokens...");
        float[] firstEmb = getEmbeddingNormalized(sorted[0]);
        int dim = firstEmb.length;

        float[] allVectors = new float[sorted.length * dim];
        System.arraycopy(firstEmb, 0, allVectors, 0, dim);
        for (int i = 1; i < sorted.length; i++) {
            float[] emb = getEmbeddingNormalized(sorted[i]);
            System.arraycopy(emb, 0, allVectors, i * dim, dim);
        }

        FlatL2Index index = new FlatL2Index(dim);
        index.add(sorted.length, allVectors);
        vocabulary = new Vocabulary(index, sorted);
        LOG.info("Training vocabulary ready: " + sorted.length + " tokens.");
        return sorted.length;
    }

    /** @return the number of tokens of the current vocabulary, 0 when there is none */
    public int vocabularySize() {
        Vocabulary current = vocabulary;
        return current == null ? 0 : current.tokens().length;
    }

    // -----------------------------------------------------------------------
    // Math helpers
    // -----------------------------------------------------------------------

    /**
     * L2-normalises {@code v} in-place: divides each component by the vector's
     * Euclidean norm.  Mirrors Python's {@code faiss.normalize_L2()}.
     */
    private static void normalizeL2(float[] v) {
        float norm = 0f;
        for (float x : v) norm += x * x;
        norm = (float) Math.sqrt(norm);
        if (norm > 1e-8f) {
            for (int i = 0; i < v.length; i++) v[i] /= norm;
        }
    }

    /**
     * Dot product between {@code query} and the stored vector at position
     * {@code idx} in the raw flat array.
     * For unit vectors this equals the cosine similarity.
     */
    private static float innerProduct(float[] query, float[] allVectors, int idx, int dim) {
        float sum = 0f;
        int base = idx * dim;
        for (int j = 0; j < dim; j++) sum += query[j] * allVectors[base + j];
        return sum;
    }

    // -----------------------------------------------------------------------
    // FastText embedding
    // -----------------------------------------------------------------------

    private float[] getEmbedding(String word) {
        List<Float> vec = fastText.getVector(word);
        float[] arr = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) arr[i] = vec.get(i);
        return arr;
    }

    /** Returns the FastText embedding of {@code word} normalised to unit length. */
    private float[] getEmbeddingNormalized(String word) {
        float[] v = getEmbedding(word);
        normalizeL2(v);
        return v;
    }

    // -----------------------------------------------------------------------
    // Signature helpers
    // -----------------------------------------------------------------------

    /** Converts a list of training token strings to their SHA-256 hashes. */
    private List<String> toHashes(List<String> tokens) {
        List<String> hashes = new ArrayList<>(tokens.size());
        for (String t : tokens) hashes.add(sha256Hex(salt, t));
        return hashes;
    }

    private String sha256Hex(byte[] saltBytes, String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(saltBytes);
            md.update(value.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).trim();
    }
}

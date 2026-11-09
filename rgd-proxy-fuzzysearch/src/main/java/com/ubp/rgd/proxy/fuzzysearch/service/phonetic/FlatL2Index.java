package com.ubp.rgd.proxy.fuzzysearch.service.phonetic;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Pure-Java flat L2 index implementing the same semantics as
 * {@code faiss::IndexFlatL2} (exact brute-force nearest-neighbour search
 * using squared Euclidean distance).
 *
 * <h2>Why not the native FAISS library?</h2>
 * The bytedeco FAISS JavaCPP preset is not published to Maven Central.
 * For corpora up to ~100K vectors this brute-force implementation is fast
 * enough (10K × 300-dim query: &lt; 5 ms on modern hardware) and has no
 * native dependencies.
 *
 * <h2>Usage</h2>
 * <pre>
 *   // Build
 *   FlatL2Index index = new FlatL2Index(300);
 *   index.add(vectorsFloat, numVectors);
 *
 *   // Persist
 *   FlatL2Index.save(index, Path.of("models/name_vectors.faiss"));
 *
 *   // Load
 *   FlatL2Index index = FlatL2Index.load(Path.of("models/name_vectors.faiss"));
 *
 *   // Search
 *   long[] indices = index.search(queryVector, 5);
 * </pre>
 */
public class FlatL2Index implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Dimensionality of every stored vector. */
    private final int dimension;

    /**
     * Row-major flat array of all stored vectors.
     * Vector {@code i} occupies positions {@code [i*dimension … (i+1)*dimension)}.
     */
    private float[] vectors;

    /** Number of stored vectors. */
    private int ntotal;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Creates an empty index for vectors of the given dimensionality.
     *
     * @param dimension number of floats per vector (e.g. 300 for FastText)
     */
    public FlatL2Index(int dimension) {
        this.dimension = dimension;
        this.vectors   = new float[0];
        this.ntotal    = 0;
    }

    // -----------------------------------------------------------------------
    // Mutators
    // -----------------------------------------------------------------------

    /**
     * Adds {@code n} vectors to the index.
     *
     * @param n           number of vectors
     * @param allVectors  row-major array of length {@code n × dimension}
     */
    public void add(int n, float[] allVectors) {
        if (allVectors.length != n * dimension) {
            throw new IllegalArgumentException(
                    "Expected " + (n * dimension) + " floats, got " + allVectors.length);
        }
        this.vectors = Arrays.copyOf(allVectors, allVectors.length);
        this.ntotal  = n;
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    /**
     * Returns the indices of the {@code k} nearest stored vectors to
     * {@code query}, ordered nearest-first (smallest squared L2 distance
     * first).
     *
     * <p>If the index contains fewer than {@code k} vectors, all indices are
     * returned.  Invalid results (empty index) produce an empty array.
     *
     * @param query  float array of length {@link #dimension}
     * @param k      number of nearest neighbours to retrieve
     * @return array of indices into the original add-order, length ≤ k
     */
    public long[] search(float[] query, int k) {
        if (ntotal == 0 || k <= 0) return new long[0];
        int actualK = Math.min(k, ntotal);

        // Max-heap keyed by distance: keeps only the k closest seen so far.
        // Entry layout: float[2] = {(float) index, squaredDistance}
        PriorityQueue<float[]> heap = new PriorityQueue<>(
                actualK + 1, (a, b) -> Float.compare(b[1], a[1]));

        for (int i = 0; i < ntotal; i++) {
            float dist = squaredL2(query, i);

            if (heap.size() < actualK) {
                heap.offer(new float[]{ i, dist });
            } else {
                float[] top = heap.peek();
                if (dist < top[1]) {
                    heap.poll();
                    heap.offer(new float[]{ i, dist });
                }
            }
        }

        // Drain heap and sort by distance ascending (nearest first)
        float[][] entries = heap.toArray(new float[0][]);
        Arrays.sort(entries, (a, b) -> Float.compare(a[1], b[1]));

        long[] result = new long[entries.length];
        for (int i = 0; i < entries.length; i++) {
            result[i] = (long) entries[i][0];
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** Number of vectors currently in the index. */
    public int ntotal() { return ntotal; }

    /** Vector dimensionality. */
    public int dimension() { return dimension; }

    /**
     * Returns a direct reference to the internal flat vector array.
     * Vector {@code i} occupies positions {@code [i*dimension … (i+1)*dimension)}.
     * Do <strong>not</strong> modify the returned array.
     */
    public float[] getVectors() { return vectors; }

    // -----------------------------------------------------------------------
    // Persistence (Java serialisation — compatible with FAISS file convention)
    // -----------------------------------------------------------------------

    /**
     * Saves the index to {@code path} using Java object serialisation.
     * The file extension {@code .faiss} is used by convention to signal
     * FAISS-like index files, even though the format is Java-specific here.
     *
     * @param index the index to persist
     * @param path  destination file (parent directories must exist)
     * @throws java.io.IOException on write failure
     */
    public static void writeIndex(FlatL2Index index, java.nio.file.Path path)
            throws java.io.IOException {
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(
                new java.io.BufferedOutputStream(
                        java.nio.file.Files.newOutputStream(path)))) {
            oos.writeObject(index);
        }
    }

    /**
     * Loads a {@link FlatL2Index} from the given file (previously written
     * with {@link #writeIndex}).
     *
     * @param path source file
     * @return the deserialised index
     * @throws java.io.IOException            on read failure
     * @throws ClassNotFoundException if the class is not in the classpath
     */
    public static FlatL2Index readIndex(java.nio.file.Path path)
            throws java.io.IOException, ClassNotFoundException {
        try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(
                new java.io.BufferedInputStream(
                        java.nio.file.Files.newInputStream(path)))) {
            return (FlatL2Index) ois.readObject();
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /** Squared L2 distance between {@code query} and stored vector {@code i}. */
    private float squaredL2(float[] query, int i) {
        float sum  = 0f;
        int   base = i * dimension;
        for (int j = 0; j < dimension; j++) {
            float d = query[j] - vectors[base + j];
            sum += d * d;
        }
        return sum;
    }
}

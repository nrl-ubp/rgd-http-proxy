package com.ubp.rgd.proxy.fuzzysearch.service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenSearch-backed implementation of {@link OpenSearchPersonIndex}.
 *
 * <p>Uses the Elasticsearch low-level REST client (compatible with OpenSearch).
 * Configure the target cluster in {@code application.properties}:
 * <pre>
 *   quarkus.elasticsearch.hosts=localhost:9200
 *   quarkus.elasticsearch.protocol=http
 * </pre>
 *
 * <h2>Index mapping (expected)</h2>
 * <pre>
 *   PUT /person_search_v1
 *   {
 *     "mappings": {
 *       "properties": {
 *         "docId":    { "type": "keyword" },
 *         "tenantId": { "type": "keyword" },
 *         "phonetic": { "type": "keyword" }
 *       }
 *     }
 *   }
 * </pre>
 */
@ApplicationScoped
public class OpenSearchPersonIndexImpl implements OpenSearchPersonIndex {

    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchPersonIndexImpl.class);
    private static final String INDEX = "person_search_v1";

    /** Maximum number of hits returned by a single search (safety cap). */
    private static final int SEARCH_SIZE = 10_000;

    @Inject
    RestClient restClient;

    /** Jackson, thread-safe once configured. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Whether {@link #ensureIndex()} ran: checked on first use, not when the bean is created. */
    private volatile boolean indexChecked;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Ensures the {@value #INDEX} OpenSearch index exists with the required
     * <strong>{@code keyword}</strong> field mappings before any document is
     * indexed or searched.
     *
     * <h3>Why keyword mapping matters</h3>
     * <ul>
     *   <li><strong>{@code phonetic}</strong> – SHA-256 hex strings must be
     *       stored verbatim; a {@code text} field with the default analyser
     *       would tokenise them (splitting on character boundaries depending on
     *       the tokeniser), breaking {@code terms} exact-match queries.</li>
     *   <li><strong>{@code tenantId}</strong> – values like {@code "tenant-1"}
     *       would be split into {@code "tenant"} and {@code "1"} by the
     *       standard analyser.  A {@code term} filter on the {@code text} field
     *       would then never match the full value, silently returning zero
     *       results for every search.</li>
     *   <li><strong>{@code docId}</strong> – same reasoning as {@code tenantId}.</li>
     * </ul>
     *
     * <p>If the index already exists this method is a no-op (OpenSearch returns
     * 200 to the HEAD request and the PUT is skipped). It runs on the first
     * use of the index rather than at startup, so that a disabled fuzzy search
     * never contacts OpenSearch; a failure is retried on the next use.
     */
    synchronized void ensureIndex() {
        if (indexChecked) {
            return;
        }
        // 1. Check whether the index already exists (HEAD returns 200 or 404)
        Request headRequest = new Request("HEAD", "/" + INDEX);
        try {
            Response resp = restClient.performRequest(headRequest);
            int retCode = resp.getStatusLine().getStatusCode();
            if (retCode == 200) {
                LOG.info("OpenSearch index '" + INDEX + "' already exists.");
                indexChecked = true;
                return; // nothing to do
            } else {
                throw new ResponseException(resp);
            }
        } catch (ResponseException re) {
            // ignore 404 since it means that we need to create the index in opensearch
            if (re.getResponse().getStatusLine().getStatusCode() != 404) {
                LOG.error(
                        "Unexpected HTTP status checking OpenSearch index '" + INDEX + "'", re);
                return;
            }
        } catch (IOException e) {
            LOG.error(
                    "Could not contact OpenSearch to check index '" + INDEX
                            + "'. Searches may fail if the mapping is wrong.", e);
            return;
        }

        // 2. Create the index with explicit keyword mappings.
        //    All three fields are pure identifiers / hashes that must never
        //    be analysed (tokenised, lowercased, etc.) by OpenSearch.
        String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "docId":          { "type": "keyword" },
                      "tenantId":       { "type": "keyword" },
                      "phonetic":       { "type": "keyword" },
                      "phonetic_prefix": { "type": "keyword" },
                      "phonetic_suffix": { "type": "keyword" }
                    }
                  }
                }
                """;

        Request putRequest = new Request("PUT", "/" + INDEX);
        putRequest.setEntity(new StringEntity(mapping, ContentType.APPLICATION_JSON));
        try {
            restClient.performRequest(putRequest);
            LOG.info("Created OpenSearch index '" + INDEX + "' with keyword mappings.");
            indexChecked = true;
        } catch (IOException e) {
            LOG.error(
                    "Failed to create OpenSearch index '" + INDEX
                            + "'. Searches will not work correctly.", e);
        }
    }

    // -----------------------------------------------------------------------
    // OpenSearchPersonIndex
    // -----------------------------------------------------------------------

    @Override
    public void index(PersonSearchDocument doc) {
        ensureIndex();
        String body = toJson(doc);
        Request request = new Request("PUT", "/" + INDEX + "/_doc/" + doc.docId);
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        try {
            restClient.performRequest(request);
            LOG.debug("Indexed document: " + doc.docId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to index document " + doc.docId, e);
        }
    }

    @Override
    public long deleteTenant(String tenantId) {
        ensureIndex();
        String body = toJson(Map.of("query", Map.of("term", Map.of("tenantId", tenantId))));
        Request request = new Request("POST", "/" + INDEX + "/_delete_by_query");
        request.addParameter("refresh", "true");
        request.addParameter("conflicts", "proceed");
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        try {
            Response response = restClient.performRequest(request);
            JsonNode deleted = MAPPER.readTree(response.getEntity().getContent()).get("deleted");
            if (deleted == null || !deleted.isNumber()) {
                throw new IllegalStateException("_delete_by_query answered without a 'deleted' count");
            }
            LOG.info("Deleted " + deleted.asLong() + " document(s) of tenant '" + tenantId + "'.");
            return deleted.asLong();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to delete the documents of tenant " + tenantId, e);
        }
    }

    @Override
    public void refresh() {
        try {
            restClient.performRequest(new Request("POST", "/" + INDEX + "/_refresh"));
        } catch (IOException e) {
            LOG.warn( "Failed to refresh index '" + INDEX + "'.", e);
        }
    }

    /**
     * Searches for persons whose {@code phonetic} field contains ANY of the
     * given signatures AND whose {@code tenantId} matches.
     *
     * <p>Generated query:
     * <pre>
     * {
     *   "bool": {
     *     "must":   [ { "terms": { "phonetic": [...] } } ],
     *     "filter": [ { "term":  { "tenantId": "..." } } ]
     *   }
     * }
     * </pre>
     *
     * @return list of person IDs in OpenSearch score order (highest first)
     */
    @Override
    public List<Long> searchByPhonetic(String tenantId, List<String> phoneticSignatures) {
        if (phoneticSignatures == null || phoneticSignatures.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Object> query = Map.of(
                "size",    SEARCH_SIZE,
                "_source", List.of("docId"),
                "query", Map.of(
                        "bool", Map.of(
                                "must", List.of(
                                        Map.of("terms", Map.of("phonetic", phoneticSignatures))
                                ),
                                "filter", List.of(
                                        Map.of("term", Map.of("tenantId", tenantId))
                                )
                        )
                )
        );

        ensureIndex();
        String body = toJson(query);
        Request request = new Request("POST", "/" + INDEX + "/_search");
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));

        try {
            Response response = restClient.performRequest(request);
            return extractDocIds(response);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to search by phonetic signatures", e);
        }
    }

    /**
     * Boosted phonetic search with positional scoring.
     *
     * <p>Sends a single {@code bool/should} query with up to four
     * {@code terms} clauses:
     * <ul>
     *   <li>{@code phonetic_prefix} boost=4.0 → starts-with</li>
     *   <li>{@code phonetic_suffix} boost=3.0 → ends-with</li>
     *   <li>{@code phonetic} (query) boost=2.0 → contains</li>
     *   <li>{@code phonetic} (neighbours) boost=1.0 → fuzzy recall</li>
     * </ul>
     */
    @Override
    public List<Long> searchByPhoneticBoosted(String tenantId,
                                              List<String> prefixSigs,
                                              List<String> suffixSigs,
                                              List<String> containsSigs,
                                              List<String> neighbourSigs) {

        List<Map<String, Object>> shouldClauses = new ArrayList<>();

        if (prefixSigs != null && !prefixSigs.isEmpty()) {
            shouldClauses.add(Map.of("terms",
                    Map.of("phonetic_prefix", prefixSigs, "boost", 4.0)));
        }
        if (suffixSigs != null && !suffixSigs.isEmpty()) {
            shouldClauses.add(Map.of("terms",
                    Map.of("phonetic_suffix", suffixSigs, "boost", 3.0)));
        }
        if (containsSigs != null && !containsSigs.isEmpty()) {
            shouldClauses.add(Map.of("terms",
                    Map.of("phonetic", containsSigs, "boost", 2.0)));
        }
        if (neighbourSigs != null && !neighbourSigs.isEmpty()) {
            shouldClauses.add(Map.of("terms",
                    Map.of("phonetic", neighbourSigs, "boost", 1.0)));
        }

        if (shouldClauses.isEmpty()) {
            return Collections.emptyList();
        }

        java.util.LinkedHashMap<String, Object> boolClause = new java.util.LinkedHashMap<>();
        boolClause.put("should",              shouldClauses);
        boolClause.put("minimum_should_match", 1);
        boolClause.put("filter", List.of(
                Map.of("term", Map.of("tenantId", tenantId))
        ));

        Map<String, Object> query = Map.of(
                "size",    SEARCH_SIZE,
                "_source", List.of("docId"),
                "query",   Map.of("bool", boolClause)
        );

        ensureIndex();
        String body = toJson(query);
        Request request = new Request("POST", "/" + INDEX + "/_search");
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));

        try {
            Response response = restClient.performRequest(request);
            return extractDocIds(response);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to execute boosted phonetic search", e);
        }
    }

    // -----------------------------------------------------------------------
    // Response parsing
    // -----------------------------------------------------------------------

    /**
     * Parses the OpenSearch {@code _search} response and extracts the numeric
     * person ID from each hit's {@code _source.docId} field.
     *
     * <p>The {@code docId} format is {@code "person-<id>"}, e.g.:
     * {@code "person-42"} → {@code 42L}.
     *
     * @param response the raw HTTP response from OpenSearch
     * @return ordered list of person IDs found in the response
     * @throws IOException if the response body cannot be read
     */
    private List<Long> extractDocIds(Response response) throws IOException {
        JsonNode root = MAPPER.readTree(response.getEntity().getContent());

        // root → hits → hits[]
        JsonNode hits = root.path("hits").path("hits");
        if (!hits.isArray()) {
            LOG.warn("OpenSearch response missing 'hits' array.");
            return Collections.emptyList();
        }
        LOG.debug("OpenSearch returned " + hits.size() + " hit(s).");

        List<Long> result = new ArrayList<>(hits.size());
        for (JsonNode hit : hits) {
            String docId = hit.path("_source").path("docId").asText(null);
            if (docId == null) {
                continue;
            }
            // "person-123" → 123L
            int sep = docId.lastIndexOf('-');
            if (sep >= 0) {
                try {
                    result.add(Long.parseLong(docId.substring(sep + 1)));
                } catch (NumberFormatException ex) {
                    LOG.warn("Unexpected docId format (skipped): " + docId);
                }
            }
        }
        return result;
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialise an OpenSearch request", e);
        }
    }
}

package com.ubp.rgd.proxy.fuzzysearch.resource;

import com.ubp.rgd.proxy.fuzzysearch.FuzzySearch;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import com.ubp.rgd.proxy.fuzzysearch.dto.PhoneticNeighboursResponse;
import com.ubp.rgd.proxy.fuzzysearch.service.phonetic.FastTextPhoneticSignatureService;
import com.ubp.rgd.proxy.fuzzysearch.service.phonetic.PhoneticSignatureService;

import java.util.List;

/**
 * Diagnostic / debug REST endpoints for the phonetic signature model.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /phonetic/neighbours?term=Nicola&topN=5}</li>
 * </ul>
 *
 * <p>These endpoints are <strong>not</strong> privacy-sensitive (they operate
 * on clear-text training vocabulary only) and should be disabled or
 * access-controlled in production environments.
 */
@Path(FuzzySearch.BASE_PATH + "/phonetic")
@FuzzySearchEndpoint
@Tag(name = FuzzySearch.TAG, description = "Fuzzy person search over the protected PERSON table")
@Produces(MediaType.APPLICATION_JSON)
public class PhoneticResource {

    /**
     * Inject the concrete service (not the interface) so we can access
     * the {@code neighbourTokens()} method that is not part of the
     * {@link PhoneticSignatureService} interface.
     */
    @Inject
    FastTextPhoneticSignatureService signatureService;

    /**
     * Returns the training-vocabulary tokens whose KMeans cluster is among
     * the {@code topN} nearest centroids to the given {@code term}.
     *
     * <p>Use this endpoint to validate and tune the fuzzy-search behaviour:
     * <ul>
     *   <li>Query {@code "Nicola"} should return neighbours such as
     *       {@code "nicolas"}, {@code "nichola"}, {@code "nicholas"}, etc.</li>
     *   <li>Increase {@code topN} for more neighbours (looser matching).</li>
     *   <li>Decrease {@code topN} for fewer but more precise neighbours.</li>
     * </ul>
     *
     * <h3>Example request</h3>
     * <pre>
     *   GET /phonetic/neighbours?term=Nicola&amp;topN=5
     * </pre>
     *
     * <h3>Example response</h3>
     * <pre>
     * {
     *   "term":            "Nicola",
     *   "topN":            5,
     *   "totalNeighbours": 12,
     *   "neighbours":      ["nicolas", "nichola", "nicholas", "nicolo", "cola", ...]
     * }
     * </pre>
     *
     * @param term  the input name token to look up
     * @param topN  number of nearest KMeans clusters to explore (default 5)
     * @return {@link PhoneticNeighboursResponse} with clear-text neighbour tokens
     */
    @GET
    @Path("/neighbours")
    public PhoneticNeighboursResponse neighbours(
            @QueryParam("term") String term,
            @QueryParam("topN") @DefaultValue("5") int topN) {

        if (term == null || term.isBlank()) {
            return new PhoneticNeighboursResponse("", topN, 0, List.of());
        }

        List<String> neighbours = signatureService.neighbourTokens(term.trim(), topN);
        return new PhoneticNeighboursResponse(term.trim(), topN, neighbours.size(), neighbours);
    }
}

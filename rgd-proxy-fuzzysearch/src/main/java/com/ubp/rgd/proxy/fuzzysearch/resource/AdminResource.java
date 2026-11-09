package com.ubp.rgd.proxy.fuzzysearch.resource;

import com.ubp.rgd.proxy.fuzzysearch.FuzzySearch;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import com.ubp.rgd.proxy.fuzzysearch.dto.ReindexResponse;
import com.ubp.rgd.proxy.fuzzysearch.dto.TrainingDataResponse;
import com.ubp.rgd.proxy.fuzzysearch.service.search.TrainingDataService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Administrative operations on the fuzzy search data.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /admin/reindex} – clean rebuild of the OpenSearch index
 *       and of the training vocabulary from the protected {@code PERSON}
 *       table.</li>
 *   <li>{@code POST /admin/training-data} – rebuild of the training
 *       vocabulary only.</li>
 * </ul>
 * Both read the table page by page and unprotect the names with the
 * transformer of the rgd-http-proxy, see {@link TrainingDataService}. Only one
 * run at a time: a request arriving while one is running gets HTTP 409.
 */
@Path(FuzzySearch.BASE_PATH + "/admin")
@FuzzySearchEndpoint
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = FuzzySearch.TAG, description = "Fuzzy person search over the protected PERSON table")
public class AdminResource {

    @Inject
    TrainingDataService trainingDataService;

    /**
     * Clean reindex of every person of the {@code PERSON} table.
     *
     * <ol>
     *   <li>The documents of {@code fuzzy-search.tenant-id} are deleted from
     *       OpenSearch, so no document of a deleted row survives.</li>
     *   <li>The table is read page by page; the names of each page are
     *       unprotected in a single transformer call, then the phonetic
     *       signatures are recomputed and the documents indexed.</li>
     *   <li>The training vocabulary is rebuilt from the names of the same
     *       pass.</li>
     * </ol>
     * Individual failures are logged and counted but do not abort the run.
     * The fuzzy search returns partial results while it runs.
     *
     * @param batchSize rows read and unprotected per page
     * @return the counts of the run
     */
    @POST
    @Path("/reindex")
    @Operation(
            summary     = "Clean reindex of all persons into OpenSearch",
            description = "Deletes the documents of the tenant, unprotects the names of every row of "
                        + "the PERSON table with the proxy transformer, indexes them again and "
                        + "rebuilds the training vocabulary.")
    @APIResponse(responseCode = "200", description = "Reindex completed (partial errors reflected in the 'errors' field)")
    @APIResponse(responseCode = "409", description = "A reindex or training data run is already in progress")
    public ReindexResponse reindex(
            @QueryParam("batchSize")
            @DefaultValue("500")
            @Parameter(description = "Number of rows read and unprotected per page (default 500)")
            int batchSize) {
        try {
            TrainingDataService.ReindexResult result = trainingDataService.reindex(batchSize);
            return new ReindexResponse(result.deleted(), result.total(), result.indexed(), result.errors(),
                    result.vocabularySize(), result.durationMs());
        } catch (TrainingDataService.AlreadyRunningException e) {
            throw new ClientErrorException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    /**
     * Rebuilds the training vocabulary of the fuzzy pass from the names of
     * the {@code PERSON} table, unprotected with the proxy transformer. The
     * vocabulary is kept in memory only.
     *
     * @param batchSize rows read and unprotected per page
     * @return the rows read and the size of the vocabulary
     */
    @POST
    @Path("/training-data")
    @Operation(
            summary     = "Rebuild the training vocabulary from the database",
            description = "Unprotects the names of the PERSON table with the proxy transformer and "
                        + "rebuilds the in-memory FastText neighbour vocabulary.")
    @APIResponse(responseCode = "200", description = "Vocabulary rebuilt")
    @APIResponse(responseCode = "409", description = "A reindex or training data run is already in progress")
    public TrainingDataResponse trainingData(
            @QueryParam("batchSize")
            @DefaultValue("500")
            @Parameter(description = "Number of rows read and unprotected per page (default 500)")
            int batchSize) {
        try {
            TrainingDataService.TrainingResult result = trainingDataService.rebuildVocabulary(batchSize);
            return new TrainingDataResponse(result.rows(), result.vocabularySize(), result.durationMs());
        } catch (TrainingDataService.AlreadyRunningException e) {
            throw new ClientErrorException(e.getMessage(), Response.Status.CONFLICT);
        }
    }
}

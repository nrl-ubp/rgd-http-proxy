package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.Context;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Transforms the data of an endpoint, whatever the underlying tokenizer.
 * <p>
 * Everything that does not depend on the tokenizer, that is reading the configuration, locating the
 * values to transform in the payload, the headers and the query parameters, and writing the results
 * back, is implemented once in {@link AbstractEndPointTransformer}. An implementation only has to
 * provide {@link #transformData}, which is where the values are actually protected or unprotected.
 *
 * @see RPSEndPointTransformer tokenization through the RegData RPS engine
 * @see FPEEndPointTransformer encryption through Format Preserving Encryption
 */
public interface EndPointTransformer {

    /**
     * Protect or unprotect the given values, in place.
     * <p>
     * The action to apply is carried by the {@code Action} evidence of the processing context. Every
     * value must come back with a transformed value set, even when it is left unchanged, otherwise
     * reassembling it fails.
     *
     * @param values            the values to transform
     * @param rightContext      the right context of the endpoint
     * @param processingContext the processing context of the endpoint, carrying the action
     * @throws Exception when the transformation cannot be performed
     */
    void transformData(IRPSValue<String>[] values, Context rightContext, ProcessingContext processingContext) throws Exception;

    /**
     * Get the transformation configuration matching an endpoint.
     *
     * @param apiMethod the HTTP method of the request
     * @param apiPath   the path of the API
     * @param when      {@code BEFORE} or {@code AFTER}, that is request or response
     * @return the matching configuration, or {@code null} when nothing has to be transformed
     */
    EndPointTransformConfig getEndpointTransformConfig(String apiMethod, String apiPath, String when);

    /**
     * Apply the transformation described by an endpoint configuration to a request or a response.
     *
     * @param json            the JSON payload
     * @param headers         the headers, transformed in place
     * @param queryParameters the query parameters, transformed in place
     * @param cfg             the endpoint transformation configuration
     * @return the transformed JSON payload
     * @throws RPSTransformException when the transformation fails
     * @see #getEndpointTransformConfig(String, String, String)
     */
    String transform(String json,
                     MultivaluedMap<String, String> headers,
                     MultivaluedMap<String, String> queryParameters,
                     EndPointTransformConfig cfg) throws RPSTransformException;

    /**
     * Apply the transformation to a standalone JSON document.
     * <p>
     * This is the payload-only counterpart of
     * {@link #transform(String, MultivaluedMap, MultivaluedMap, EndPointTransformConfig)}, for the
     * callers holding a JSON document rather than an HTTP exchange, such as the file transformation.
     * The action is read from the {@code Action} processing context evidence.
     *
     * @param json                        the JSON document
     * @param attributeConfigs            the entity transformations, keyed by configured JSON path
     * @param rightContextEvidences       the evidences of the right context
     * @param processingContextEvidences  the evidences of the processing context, carrying the action
     * @return the transformed JSON document
     * @throws RPSTransformException when the transformation fails
     */
    String transformJson(String json,
                         Map<String, EntityTransformConfig> attributeConfigs,
                         Map<String, String> rightContextEvidences,
                         Map<String, String> processingContextEvidences) throws RPSTransformException;

    /**
     * The pattern recognising a token this transformer produces.
     * <p>
     * Needed by the callers that have to locate tokens in data carrying no transformation
     * configuration, such as the Flight SQL result sets: what a token looks like depends entirely on
     * the implementation, so it cannot be hardcoded by the caller.
     *
     * @return the token pattern, matching the whole token including its <code>RG{</code> wrapper
     */
    Pattern tokenPattern();

    /**
     * Whether the tokens of this transformer carry an RPS mapping index in their first two
     * characters.
     * <p>
     * That index is what lets a token be resolved to a class and a property without any mapping
     * configuration. Only the RegData engine writes it, so a caller must check this before trying to
     * read it: interpreting the first two characters of another token format would attach an
     * arbitrary mapping to the value.
     *
     * @return {@code true} when the mapping index can be read from the tokens
     */
    boolean supportsTokenMappingIndex();
}

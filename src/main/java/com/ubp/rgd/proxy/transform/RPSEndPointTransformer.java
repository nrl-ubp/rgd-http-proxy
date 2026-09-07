package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.*;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import com.ubp.rgd.proxy.transform.config.HeaderTransformConfig;
import com.ubp.rgd.proxy.transform.config.UrlQueryTransformConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

@ApplicationScoped
public class RPSEndPointTransformer {

    private static final Logger LOG = LoggerFactory.getLogger(RPSEndPointTransformer.class);

    /**
     * Makes {@code read()} return the concrete path of every match instead of its value.
     */
    private static final Configuration PATH_LIST_CONFIG = Configuration.builder()
            .options(Option.AS_PATH_LIST)
            .build();

    /**
     * Read a JSON path and always return a list of values.
     * <p>
     * {@link DocumentContext#read(String)} only returns a list for indefinite paths (containing a
     * wildcard, a deep scan or a filter). A definite path such as {@code $.name} returns the raw
     * value itself, which may be a String, a number, a boolean or {@code null}.
     *
     * @param documentContext the parsed JSON document
     * @param jsonPath        the configured JSON path
     * @return the matching values, never {@code null}
     * @throws PathNotFoundException when the document does not carry the path
     */
    private static List<Object> readValues(DocumentContext documentContext, String jsonPath) {
        Object result = documentContext.read(jsonPath);
        if (result instanceof List) {
            return (List<Object>) result;
        }
        // Definite path: a single value, possibly null.
        return Collections.singletonList(result);
    }

    @Inject
    RPSClientEngineProvider engineProvider;

    @ConfigProperty(name = "proxy.transform.config-file", defaultValue="./config/rps_transform_config.json")
    String transformConfigFile;

    /**
     * List of end point configuration mapping the methods and path to transform
     */
    private List<EndPointTransformConfig> endpointConfig;

    @PostConstruct
    public void init() throws IOException {
        LOG.info("Now loading transform configuration from {}", transformConfigFile);
        ObjectMapper objectMapper = new ObjectMapper();
        endpointConfig = objectMapper.readValue(new File(transformConfigFile), new TypeReference<>() { } );
        if (endpointConfig == null) {
            LOG.error("Transform config is null and has not been loaded.");
        }
    }

    private Context getRightContext(EndPointTransformConfig cfg) {
        Context rightContext = new Context();

        cfg.getRightContextEvidences().forEach((key, value) -> {
            Evidence moduleEvidence = new Evidence();
            moduleEvidence.setName(key);
            moduleEvidence.setValue(value);
            rightContext.addEvidence(moduleEvidence);
            LOG.debug("Right context: {} = {}", key, value);
        });

        return rightContext;
    }

    private ProcessingContext getProcessingContext(EndPointTransformConfig cfg) {
        ProcessingContext processingContext = new ProcessingContext();

        cfg.getProcessingContextEvidences().forEach((key, value) -> {
            Evidence evidence = new Evidence();
            evidence.setName(key);
            evidence.setValue(value);
            processingContext.addEvidence(evidence);

            LOG.debug("Processing context: {} = {}", key, value);
        });

        return processingContext;
    }

    /**
     * Get the EndPointTransformConfig that matches the apiMethod and the apiPAth.
     * WARNING: If more that one config matches the apiPath then the FIRST matched config is returned.
     * Be sure to have one config per method, path and when (BEFORE, AFTER) triplet.
     * @param apiMethod the method to protect GET, POST, PUT, DELETE ...
     * @param apiPath the path of the API
     * @param when AFTER or BEFORE means if we need to apply transformation after the proxified API resource to be invoked.
     * @return EndPointTransformConfig corresponding to the parameters. Null if none found, meaning, no transform required.
     */
    public EndPointTransformConfig getEndpointTransformConfig(String apiMethod, String apiPath, String when) {
        List<EndPointTransformConfig> matched = endpointConfig.stream().filter(cfg -> cfg.getEndpointMethods().contains(apiMethod.toUpperCase()) &&
                Pattern.matches(cfg.getEndpointPath(), apiPath) && cfg.getEndpointTransformWhen().equalsIgnoreCase(when)).toList();

        if (matched.size() > 1) {
            LOG.warn("DUPLICATE endpoint transformation configuration detected for {} > {} > {} ", apiMethod, when, apiPath);
            LOG.warn("Using first configuration encountered. You must fix the configuration file: {}", transformConfigFile);
        }

        return matched.isEmpty() ? null : matched.getFirst();
    }

    /**
     * Read every value matching the configured JSON paths and build the RPS values to transform.
     * <p>
     * The returned map is keyed by the <b>concrete</b> JSON path of each match (for instance
     * {@code $['persons'][1]['name']}) so that the transformed value can later be written back
     * exactly where it was read from.
     *
     * @param documentContext   the parsed JSON document
     * @param attributesConfigs the transformation configuration, keyed by configured JSON path
     * @return the RPS values to transform, keyed by concrete JSON path
     */
    protected Map<String, RPSValue[]> getRPSValuesFromBody(DocumentContext documentContext, Map<String, EntityTransformConfig> attributesConfigs) {
        Map<String, RPSValue[]> rpsValuesByJsonPath = new HashMap<>();

        // Reuses the already parsed document, so the payload is not parsed a second time.
        DocumentContext pathContext = JsonPath.using(PATH_LIST_CONFIG).parse((Object) documentContext.json());

        // Iterate over JSON paths and create RPS Json path values
        for (String jsonPath : attributesConfigs.keySet()) {
            List<Object> values;
            List<String> matchedPaths;
            try {
                values = readValues(documentContext, jsonPath);
                matchedPaths = pathContext.read(jsonPath);
            } catch (PathNotFoundException e) {
                // The document simply does not carry this path: nothing to transform.
                LOG.debug("Json path not found in the document, skipping it: {}", jsonPath);
                continue;
            }

            if (values.size() != matchedPaths.size()) {
                LOG.warn("Json path {} matched {} value(s) but {} path(s). Skipping it to avoid writing a value"
                        + " to the wrong place.", jsonPath, values.size(), matchedPaths.size());
                continue;
            }

            EntityTransformConfig attrCfg = attributesConfigs.get(jsonPath);
            for (int i = 0; i < values.size(); i++) {
                Object rawValue = values.get(i);
                if (rawValue == null) {
                    // A null value holds nothing to protect nor to unprotect.
                    LOG.debug("Null value for json path {}, skipping it.", matchedPaths.get(i));
                    continue;
                }
                String oldValue = String.valueOf(rawValue);
                LOG.debug("RPSValue: {} = {} : {}", oldValue, attrCfg.getRpsClassName(), attrCfg.getRpsPropertyName());
                RPSValue rpsValue = new RPSValue(new RPSMapping(attrCfg.getRpsClassName(), attrCfg.getRpsPropertyName()), oldValue);
                rpsValuesByJsonPath.put(matchedPaths.get(i), new RPSValue[]{rpsValue});
            }
        }

        return rpsValuesByJsonPath;
    }

    /**
     * Write the transformed values back into the document.
     * <p>
     * Each entry is keyed by a concrete JSON path pointing to a single match, so the value is
     * written exactly where it was read from and the document does not need to be read again.
     *
     * @param documentContext     the parsed JSON document to update
     * @param rpsValuesByJsonPath the transformed values, keyed by concrete JSON path
     */
    protected void setRPSValuesToBody(DocumentContext documentContext, Map<String, RPSValue[]> rpsValuesByJsonPath) {
        rpsValuesByJsonPath.forEach((jsonPath, rpsJsonValues) -> {
            for (RPSValue rpsValue : rpsJsonValues) {
                documentContext.set(jsonPath, rpsValue.getTransformed()); // Replace value
            }
        });
    }

    protected Map<String, RPSValue[]> getRPSValuesFromHeaders(MultivaluedMap<String, String> headers, Map<String, HeaderTransformConfig> configs) {
        Map<String, RPSValue[]> result = new HashMap<>(headers.size());
        for (String headerName : configs.keySet()) {
            HeaderTransformConfig config = configs.get(headerName);
            // any header for this transformation config ? if not skip it.
            if (headers.get(headerName) == null) {
                continue;
            }
            RPSValue[] rpsValues = headers.get(headerName).stream()
                    .map(headerValue -> new RPSValue(new RPSMapping(config.getRpsClassName(), config.getRpsPropertyName()), headerValue))
                    .toArray(RPSValue[]::new);

            result.put(headerName, rpsValues);
        }
        return result;
    }

    protected void setRPSValuesToHeaders(MultivaluedMap<String, String> headers, Map<String, RPSValue[]> rpsValuesByHeader) {
        rpsValuesByHeader.forEach((headerName, rpsValues) -> {
            List<String> newValues = Arrays.stream(rpsValues)
                    .map(RPSValue::getTransformed)
                    .toList();
            headers.put(headerName, newValues); // replace the header values by the new ones
        });
    }

    protected Map<String, RPSValue[]> getRPSValuesFromQuery(MultivaluedMap<String, String> params, Map<String, UrlQueryTransformConfig> configs) {
        Map<String, RPSValue[]> results = new HashMap<>(params.size());

        configs.forEach((paramName, config) -> {
            if (params.get(paramName) != null) {

                RPSValue[] values = params.get(paramName).stream()
                        .map(paramValue ->  new RPSValue(new RPSMapping(config.getRpsClassName(), config.getRpsPropertyName()), paramValue))
                        .toArray(RPSValue[]::new);

                results.put(paramName, values);
            }
        });

        return results;
    }

    protected void setRPSValuesToQuery(MultivaluedMap<String, String> params, Map<String, RPSValue[]> rpsValuesByParam) {
        rpsValuesByParam.forEach((paramName, rpsValues) -> {
            List<String> newValues = Arrays.stream(rpsValues)
                    .map(RPSValue::getTransformed)
                    .toList();
            params.put(paramName, newValues);
        });
    }

    /**
     * Apply the transformation using the given endpoint transformation configuration
     * @param json original JSON payload
     * @param headers is a multi valued map
     * @return JSON object String with transformed data for the request body
     * @throws RPSTransformException in case of any problem with RPS transform
     * @see #getEndpointTransformConfig(String, String, String) to get a proper config
     */
    public String transform(String json,
                            MultivaluedMap<String, String> headers,
                            MultivaluedMap<String, String> queryParameters,
                            EndPointTransformConfig cfg) throws RPSTransformException {

        Map<String, EntityTransformConfig> attributesConfigs = cfg.sortAttributeTransformsConfig();
        Map<String, HeaderTransformConfig> headersTransformConfigs = cfg.sortHeaderTransformConfig();
        Map<String, UrlQueryTransformConfig> urlQueryTransformConfigs = cfg.sortQueryTransformConfigs();

        // Parse JSON as a document to read tags to protect and set the results after RPS transform.
        DocumentContext documentContext = JsonPath.parse(json);

        Map<String, RPSValue[]> rpsValuesByJsonPath = getRPSValuesFromBody(documentContext, attributesConfigs);
        Map<String, RPSValue[]> rpsValuesByHeader = getRPSValuesFromHeaders(headers, headersTransformConfigs);
        Map<String, RPSValue[]> rpsValuesByQueryParameter = getRPSValuesFromQuery(queryParameters, urlQueryTransformConfigs);

        // create a flat list of RPS Values to optimize the call to tokenizer
        List<RPSValue> flatList = new ArrayList<>();
        rpsValuesByJsonPath.values().forEach(rpsJsonValues -> flatList.addAll(Arrays.asList(rpsJsonValues)));
        rpsValuesByHeader.values().forEach(rpsValues -> flatList.addAll(Arrays.asList(rpsValues)));
        rpsValuesByQueryParameter.values().forEach(rpsValues -> flatList.addAll(Arrays.asList(rpsValues)));

        // now call transform API
        try {
            transformData(
                    engineProvider.getClientEngineProvider(),
                    flatList.toArray(new RPSValue[0]),
                    getRightContext(cfg),
                    getProcessingContext(cfg)
            );
        } catch (Exception e) {
            LOG.error("Transform exception: ", e);
            throw new RPSTransformException(e);
        }

        // now replace the tokenized values into the original document based on their Json path and occurrence place.
        setRPSValuesToBody(documentContext, rpsValuesByJsonPath);

        // replace header values
        setRPSValuesToHeaders(headers, rpsValuesByHeader);

        // replace query parameters values
        setRPSValuesToQuery(queryParameters, rpsValuesByQueryParameter);

        // Serialize updated JSON
        return documentContext.jsonString();
    }

    /**
     * This method calls REGDATA to get the values transformed
     */
    private void transformData(HttpClientEngineProvider engineProvider, IRPSValue<String>[] values, Context rightContext, ProcessingContext processingContext) throws Exception {

        RPSEngine engine = new RPSEngine(engineProvider,
                new RPSEngineConverter(),
                new RPSEngineContextResolver(null));

        RequestContext requestContext = new RequestContext(engine, new RPSEngineContextResolver(null));

        requestContext
                .withRequest(
                        values,
                        rightContext,
                        processingContext,
                        null);

        // Calls the transformation API -> will lead to protect the data
        requestContext.transform();
    }
}

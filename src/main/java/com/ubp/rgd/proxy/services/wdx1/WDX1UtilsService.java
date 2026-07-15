package com.ubp.rgd.proxy.services.wdx1;

import ch.regdata.rps.engine.client.*;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.security.SecurityContext;
import com.ubp.rgd.proxy.services.wdx1.config.WDX1ConcatConfig;
import com.ubp.rgd.proxy.services.wdx1.config.WDX1ConcatRpsMapping;
import com.ubp.rgd.proxy.transform.RPSClientEngineProvider;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import com.ubp.rgd.proxy.utils.StringUtils;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Utility service for WDX1 to call from either the WDX1 services server OR inside a MSDynamics plugin.
 */
@ApplicationScoped
public class WDX1UtilsService {
    private static final Logger LOG = Logger.getLogger(WDX1UtilsService.class);

    @Inject
    RPSClientEngineProvider rpsClientEngineProvider;

    @Inject
    SecurityContext securityContext;

    @ConfigProperty(name = "token.utils.wdx1.authorized-spn", defaultValue = "HTTP/wdx1e01.corp.ubp.ch")
    String wdx1AuthorizedSpn;

    /**
     * Flag to activate Kerberos auth support. Disabled for unit testing
     */
    @ConfigProperty(name = "proxy.prefilter.auth-enabled", defaultValue = "true")
    String preFilterAuthEnabled;

    /**
     * WDX1 concat configuration file
     */
    @ConfigProperty(name = "proxy.wdx1.concat-config-file", defaultValue="./config/wdx1_concat_config.json")
    String wdx1ConcatConfigFile;

    /**
     * Concat service config from json file
     */
    private WDX1ConcatConfig concatConfig;

    @PostConstruct
    public void init() throws IOException {
        LOG.infof("Now loading WDX1 concat configuration from %s", wdx1ConcatConfigFile);
        ObjectMapper objectMapper = new ObjectMapper();
        concatConfig = objectMapper.readValue(new File(wdx1ConcatConfigFile), new TypeReference<>() { } );
        if (concatConfig == null) {
            LOG.error("WDX1 concat config is null and has not been loaded.");
        }
    }

    /**
     * Token concatenation : will tokenize and format the concatenation of the clear values in the request
     * @param request the concat request from WDx1 services. Format is applied to conform to WDX1 specifications
     * @return the token representing the concat of the values of the request.
     */
    public String tokenConcat(WDX1ConcatRequest request) throws RPSTransformException {

        // check calling service is WDX1
        checkAuthorization();

        // check request
        if (request == null) {
            throw new RPSTransformException("The CONCAT request is null");
        }

        // unprotect the token
        WDX1ConcatRequest unprotectedRequest = unprotect(request);

        // sanitize the names
        WDX1ConcatRequest sanitizedRequest = sanitize(unprotectedRequest);

        // format the concat result to the format CCYYYYMMDDAAAAABBBBB
        String clearFormat = String.format("%s%s%s%s",
                sanitizedRequest.getCountry(),
                sanitizedRequest.getBirthDate(),
                StringUtils.firstFiveWithHash(sanitizedRequest.getFirstName()),
                StringUtils.firstFiveWithHash(sanitizedRequest.getLastName()));


        return protect(clearFormat);
    }

    /**
     * Check that the security context contains the username that corresponds to the authorization configuration
     * property token.utils.wdx1.authorized-spn
     * @throws RPSTransformException if there isn't any security context or username not authorized
     * @see WDX1UtilsService#wdx1AuthorizedSpn property to configure it
     */
    private void checkAuthorization() throws RPSTransformException {
        if (!"true".equalsIgnoreCase(preFilterAuthEnabled)) {
            LOG.warn("Prefilter kerberos auth DISABLED. Not checking auth for CONCAT service.");
            return;
        }


        if (securityContext == null) {
            String msg = "No security context found while calling WDX1 concat utils.";
            LOG.error(msg);
            throw new RPSTransformException(msg);
        }

        String userName = securityContext.getToken().getUser();

        if (!userName.equalsIgnoreCase(wdx1AuthorizedSpn)) {
            String msg = "Username is not authorized to execute the WDX1 concat utils: %s";
            LOG.errorf(msg, userName);
            throw new RPSTransformException(String.format(msg, userName));
        }

        LOG.debugf("User %s is authorized to execute WDX1 concat utils.");
    }

    private WDX1ConcatRequest sanitize(WDX1ConcatRequest request) {
        request.setFirstName(NameSanitizer.sanitizeName(request.getFirstName()));
        request.setLastName(NameSanitizer.sanitizeName(request.getLastName()));
        request.setBirthDate(request.getBirthDate().replaceAll("-", ""));
        return request;
    }

    private String protect(String clear) throws RPSTransformException {
        RPSValue[] rpsValues = new RPSValue[1];
        rpsValues[0] = new RPSValue(new RPSMapping(concatConfig.getConcatResultClassName(), concatConfig.getConcatResultPropertyName()), clear);

        // now call transform API
        try {
            transformData(
                    rpsClientEngineProvider.getClientEngineProvider(),
                    rpsValues,
                    getRightContext(),
                    getProcessingContext("protect")
            );
        } catch (Exception e) {
            LOG.error("Transform exception: ", e);
            throw new RPSTransformException(e);
        }

        return rpsValues[0].getTransformed();
    }

    private WDX1ConcatRequest unprotect(WDX1ConcatRequest request) throws RPSTransformException {
        // generate RPS Values to transform
        Map<String, RPSValue[]> rpsValues = getRPSValues(request);

        RPSValue[] flatRPSValues = rpsValues.values().stream()
                .filter(Objects::nonNull)
                .flatMap(Arrays::stream)
                .toArray(RPSValue[]::new);

        // now call transform API which will modify the flatRPSValues and no replace
        try {
            transformData(
                    rpsClientEngineProvider.getClientEngineProvider(),
                    flatRPSValues,
                    getRightContext(),
                    getProcessingContext("Unprotect")
            );
        } catch (Exception e) {
            LOG.error("Transform exception: ", e);
            throw new RPSTransformException(e);
        }

        // now replace tokens with clear data
        setRPSValuesTransformed(rpsValues, request);

        return request;
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

    private Context getRightContext() {
        Context rightContext = new Context();

        concatConfig.getRightContextEvidences().forEach((key,value) -> {
            Evidence moduleEvidence = new Evidence();
            moduleEvidence.setName(key);
            moduleEvidence.setValue(value);
            rightContext.addEvidence(moduleEvidence);
        });

        rightContext.getEvidences().forEach(e -> LOG.debugf("Processing context: %s = %s", e.getName(), e.getValue()));

        return rightContext;
    }

    private ProcessingContext getProcessingContext(String action) {

        ProcessingContext processingContext = new ProcessingContext();

        concatConfig.getProcessingContextEvidences().forEach((key,value) -> {
             Evidence moduleEvidence = new Evidence();
            moduleEvidence.setName(key);
            moduleEvidence.setValue(value);
            processingContext.addEvidence(moduleEvidence);
        });

        // add action evidence
        Evidence moduleEvidence = new Evidence();
        moduleEvidence.setName("Action");
        moduleEvidence.setValue(action);
        processingContext.addEvidence(moduleEvidence);

        processingContext.getEvidences().forEach(e -> LOG.debugf("Processing context: %s = %s", e.getName(), e.getValue()));

        return processingContext;
    }

    private RPSMapping getRPSMapping(String mappingType) throws RPSTransformException {
        List<WDX1ConcatRpsMapping> mappingList = concatConfig.getWdx1ConcatRpsMappings().stream().filter(m -> m.getType().equalsIgnoreCase(mappingType)).toList();
        List<WDX1ConcatRpsMapping> defaultMappingList = concatConfig.getWdx1ConcatRpsMappings().stream().filter(m -> m.getType().equalsIgnoreCase("Default")).toList();

        if (defaultMappingList.isEmpty()) {
            String msg = "WDX1 CONCAT configuration error: there should be at least a rps mapping type named 'Default'";
            LOG.error(msg);
            throw new RPSTransformException(msg);
        }

        WDX1ConcatRpsMapping defaultMapping = defaultMappingList.getFirst();

        WDX1ConcatRpsMapping mapping =  mappingList.isEmpty() ? defaultMapping : mappingList.getFirst();
        return new RPSMapping(mapping.getRpsClassName(), mapping.getRpsPropertyName());
    }

    private Map<String, RPSValue[]> getRPSValues(WDX1ConcatRequest request) throws RPSTransformException {

        Map<String, RPSValue[]> values = new HashMap<>();

        values.put("firstName", getRPSValues(request.getFirstName(), getRPSMapping("name")));
        values.put("lastName", getRPSValues(request.getLastName(), getRPSMapping("name")));
        // no transform for country : values.put("country", getRPSValues(getFirstName(), getRPSMapping("country")));
        values.put("birthDate", getRPSValues(request.getTokenizationFormattedDate(request.getBirthDate(), concatConfig.getDateFormat()), getRPSMapping("date")));

        return values;
    }

    private RPSValue[] getRPSValues(String fieldValue, RPSMapping mapping) {
        String[] words = fieldValue.split(" ");
        return Arrays.stream(words).map(word -> new RPSValue(mapping, word))
                .toArray(RPSValue[]::new);
    }


    @JsonIgnore
    public void setRPSValuesTransformed(Map<String, RPSValue[]> values, WDX1ConcatRequest request) throws RPSTransformException{

        String[] words = Optional.ofNullable(values.get("firstName")).stream()
                .flatMap(Arrays::stream)
                .map(RPSValue::getTransformed)
                .filter(Objects::nonNull)
                .toArray(String[]::new);

        if (words.length == 0) {
            throw new RPSTransformException("First Names transformation did not return anything.");
        }

        request.setFirstName(String.join(" ", words));

        words = Optional.ofNullable(values.get("lastName")).stream()
                .flatMap(Arrays::stream)
                .map(RPSValue::getTransformed)
                .filter(Objects::nonNull)
                .toArray(String[]::new);

        if (words.length == 0) {
            throw new RPSTransformException("Last Names transformation did not return anything.");
        }

        request.setLastName(String.join(" ", words));

        // no transform for country setCountry(values[x].getTransformed());

        // Only one word for birthDate :-)
        String birthDate = values.get("birthDate")[0].getTransformed();
        if (birthDate == null) {
            throw new RPSTransformException("birth date transformation did not return anything.");
        }
        request.setBirthDate(birthDate);
    }

}

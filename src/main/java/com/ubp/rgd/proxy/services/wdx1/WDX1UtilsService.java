package com.ubp.rgd.proxy.services.wdx1;

import ch.regdata.rps.engine.client.*;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.security.SecurityContext;
import com.ubp.rgd.proxy.services.wdx1.config.WDX1ConcatConfig;
import com.ubp.rgd.proxy.services.wdx1.config.WDX1ConcatRpsMapping;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import com.ubp.rgd.proxy.utils.StringUtils;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility service for WDX1 to call from either the WDX1 services server OR inside a MSDynamics plugin.
 */
@ApplicationScoped
public class WDX1UtilsService {
    private static final Logger LOG = LoggerFactory.getLogger(WDX1UtilsService.class);

    /**
     * A protected value is represented by one or more tokens. Each token is prefixed by the "RG"
     * letters and its value is enclosed in single braces, e.g. {@code RG{...}}. The name fields
     * ({@code firstNameToken}, {@code lastNameToken}) may contain several of these tokens.
     * <p>
     * Deliberately kept local rather than asked to the transformer, unlike
     * {@code FlightSqlDetokenizeService}: this pattern is only used <b>symmetrically</b>, to split a
     * field into tokens and to put the clear values back at the very same places. It therefore only
     * has to be self consistent, not to tell who produced the token, and it already matches the
     * tokens of every implementation since they are all wrapped in <code>RG{...}</code>.
     */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("RG\\{[^}]*\\}");

    /**
     * Format of the date component of the concatenation key. It is fixed by the
     * {@code CCYYYYMMDDAAAAABBBBB} layout of the key, so it is not configurable.
     */
    private static final String CONCAT_KEY_DATE_FORMAT = "yyyyMMdd";

    @Inject
    EndPointTransformer transformer;

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
        LOG.info("Now loading WDX1 concat configuration from {}", wdx1ConcatConfigFile);
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
            LOG.error(String.format(msg, userName));
            throw new RPSTransformException(String.format(msg, userName));
        }

        LOG.debug("User {} is authorized to execute WDX1 concat utils.", userName);
    }

    private WDX1ConcatRequest sanitize(WDX1ConcatRequest request) {
        request.setFirstName(NameSanitizer.sanitizeName(request.getFirstName()));
        request.setLastName(NameSanitizer.sanitizeName(request.getLastName()));
        // The birth date needs no sanitizing: it was already rendered as YYYYMMDD when the
        // detokenized value was reformatted.
        return request;
    }

    private String protect(String clear) throws RPSTransformException {
        RPSValue[] rpsValues = new RPSValue[1];
        rpsValues[0] = new RPSValue(new RPSMapping(concatConfig.getConcatResultClassName(), concatConfig.getConcatResultPropertyName()), clear);

        // now call transform API
        try {
            transformer.transformData(
                    rpsValues,
                    getRightContext(),
                    getProcessingContext("protect")
            );
        } catch (Throwable e) {
            LOG.error("Transform exception: ", e);
            throw new RPSTransformException(e.getMessage());
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
            transformer.transformData(
                    flatRPSValues,
                    getRightContext(),
                    getProcessingContext("Unprotect")
            );
        } catch (Throwable e) {
            LOG.error("Transform exception: ", e);
            throw new RPSTransformException(e.getMessage());
        }

        // now replace tokens with clear data
        setRPSValuesTransformed(rpsValues, request);

        return request;
    }


    private Context getRightContext() {
        Context rightContext = new Context();

        concatConfig.getRightContextEvidences().forEach((key,value) -> {
            Evidence moduleEvidence = new Evidence();
            moduleEvidence.setName(key);
            moduleEvidence.setValue(value);
            rightContext.addEvidence(moduleEvidence);
        });

        rightContext.getEvidences().forEach(e -> LOG.debug("Processing context: {} = {}", e.getName(), e.getValue()));

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

        processingContext.getEvidences().forEach(e -> LOG.debug("Processing context: {} = {}", e.getName(), e.getValue()));

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

        // First and last names may each contain several RG{...} tokens: extract one RPS value per token.
        values.put("firstName", getTokenRPSValues(request.getFirstName(), getRPSMapping("name")));
        values.put("lastName", getTokenRPSValues(request.getLastName(), getRPSMapping("name")));
        // no transform for country : values.put("country", getRPSValues(getFirstName(), getRPSMapping("country")));
        // The birth date is a token at this point: it is sent as is for detokenization, and only the
        // clear date that comes back is reformatted, in setRPSValuesTransformed().
        values.put("birthDate", new RPSValue[]{
                new RPSValue(getRPSMapping("date"), request.getBirthDate())
        });

        return values;
    }

    /**
     * Extract every {@code RG{...}} token contained in the given field value and create one
     * {@link RPSValue} per token, in order of appearance, so they can be detokenized.
     * @param fieldValue the raw field value possibly containing several tokens (may be null)
     * @param mapping the RPS mapping to apply to each token
     * @return an array of RPS values, one per token found (empty if none)
     */
    private RPSValue[] getTokenRPSValues(String fieldValue, RPSMapping mapping) {
        if (fieldValue == null) {
            return new RPSValue[0];
        }

        List<RPSValue> rpsValues = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(fieldValue);
        while (matcher.find()) {
            rpsValues.add(new RPSValue(mapping, matcher.group()));
        }

        return rpsValues.toArray(new RPSValue[0]);
    }


    @JsonIgnore
    public void setRPSValuesTransformed(Map<String, RPSValue[]> values, WDX1ConcatRequest request) throws RPSTransformException{

        // Replace each RG{...} token, in place, by its detokenized clear value while preserving
        // the original field layout (separators, surrounding text, token ordering).
        request.setFirstName(replaceTokensWithClearValues("First Name", request.getFirstName(), values.get("firstName")));
        request.setLastName(replaceTokensWithClearValues("Last Name", request.getLastName(), values.get("lastName")));

        // no transform for country setCountry(values[x].getTransformed());

        // Only one word for birthDate :-)
        String birthDate = values.get("birthDate")[0].getTransformed();
        if (birthDate == null) {
            String error = values.get("birthDate")[0].getError().getMessage();
            throw new RPSTransformException("Birth date transformation did not return anything: " + error);
        }
        checkTransformed("Birth date", birthDate);
        request.setBirthDate(toConcatKeyDate(request, birthDate));
    }

    /**
     * Render a detokenized birth date as the {@code YYYYMMDD} component of the concatenation key.
     * <p>
     * The clear date comes back in the format declared by the caller, so it is first normalised to
     * the format the concat configuration works with, then rendered in the fixed key format. That
     * second step is what guarantees a correct key whatever the configured formats are.
     *
     * @param request the request, holding the format the caller expressed its date in
     * @param clearDate the detokenized birth date
     * @return the date as {@code YYYYMMDD}
     * @throws RPSTransformException if the clear date is not a valid date in the expected format
     */
    private String toConcatKeyDate(WDX1ConcatRequest request, String clearDate) throws RPSTransformException {
        try {
            String normalized = request.getTokenizationFormattedDate(clearDate, concatConfig.getDateFormat());

            SimpleDateFormat configuredFormat = new SimpleDateFormat(concatConfig.getDateFormat());
            configuredFormat.setLenient(false);
            return new SimpleDateFormat(CONCAT_KEY_DATE_FORMAT).format(configuredFormat.parse(normalized));
        } catch (ParseException e) {
            String msg = String.format("The detokenized birth date '%s' is not a valid date"
                    + " (expected format '%s'): %s", clearDate, request.getDateFormat(), e.getMessage());
            LOG.error(msg);
            throw new RPSTransformException(msg);
        }
    }

    /**
     * Reject a detokenized value that came back still looking like a token.
     * <p>
     * A transformer returns a value it cannot handle <b>unchanged</b> rather than {@code null}, which
     * happens here whenever the tokens come from another tokenizer than the configured one — WDX1
     * detokenizes the tokens of the upstream system, not the ones this proxy produced. Without this
     * check the concatenation key would silently be computed from the raw token text instead of the
     * clear name, then protected and stored as if it were correct.
     * <p>
     * A legitimate clear value, a name or a date, is never <code>RG{...}</code>, so this can only fire
     * on a value that was really not transformed.
     *
     * @param fieldLabel human readable field name used in the error message
     * @param clearValue the value returned by the transformer
     * @throws RPSTransformException when the value is still a token
     */
    private static void checkTransformed(String fieldLabel, String clearValue) throws RPSTransformException {
        if (clearValue != null && TOKEN_PATTERN.matcher(clearValue).matches()) {
            String msg = String.format("%s was not detokenized and came back as a token: %s."
                    + " The value was most likely produced by another tokenizer than the configured one.",
                    fieldLabel, clearValue);
            LOG.error(msg);
            throw new RPSTransformException(msg);
        }
    }

    /**
     * Rebuild a field value by substituting every {@code RG{...}} token with its detokenized clear
     * value. The transformed values must be provided in the same order as the tokens appear in the
     * original field (which is guaranteed since both use {@link #TOKEN_PATTERN}).
     * @param fieldLabel human readable field name used in error messages
     * @param originalField the original field value still holding the tokens
     * @param transformedValues the detokenized RPS values, one per token, in order of appearance
     * @return the field value with each token replaced by its clear value
     * @throws RPSTransformException if no token was detokenized or a clear value is missing
     */
    private String replaceTokensWithClearValues(String fieldLabel, String originalField, RPSValue[] transformedValues)
            throws RPSTransformException {

        RPSValue[] tokens = transformedValues == null ? new RPSValue[0] : transformedValues;

        if (tokens.length == 0) {
            throw new RPSTransformException(String.format("%s transformation did not return anything.", fieldLabel));
        }

        Matcher matcher = TOKEN_PATTERN.matcher(originalField == null ? "" : originalField);
        StringBuilder rebuilt = new StringBuilder();
        int index = 0;

        while (matcher.find() && index < tokens.length) {
            String clearValue = tokens[index++].getTransformed();
            if (clearValue == null) {
                throw new RPSTransformException(String.format(
                        "%s detokenization did not return a clear value for token: %s", fieldLabel, matcher.group()));
            }
            checkTransformed(fieldLabel, clearValue);
            matcher.appendReplacement(rebuilt, Matcher.quoteReplacement(clearValue));
        }
        matcher.appendTail(rebuilt);

        return rebuilt.toString();
    }

}

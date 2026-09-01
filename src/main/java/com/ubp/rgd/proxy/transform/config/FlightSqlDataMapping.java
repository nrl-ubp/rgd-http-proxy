package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jboss.logging.Logger;

import java.util.regex.Pattern;

/**
 * Maps a data <em>format</em> to the RPS class and property names used to detokenize it.
 * <p>
 * Data mappings drive the implicit detokenization: when a result-set column has no
 * {@link FlightSqlColumnMapping}, every data mapping's {@code regex} is used to locate its own
 * segments inside the returned values, and supplies the RPS class / property names of the segments it
 * matched. The segment sent to the engine is the whole match, so the regex must match the token
 * itself and not only a part of it.
 */
public class FlightSqlDataMapping {

    private static final Logger LOG = Logger.getLogger(FlightSqlDataMapping.class);

    @JsonProperty(value = "regex")
    private String regex;

    @JsonProperty(value = "rps-class-name")
    private String rpsClassName;

    @JsonProperty(value = "rps-property-name")
    private String rpsPropertyName;

    private transient Pattern pattern;
    private transient boolean compiled;

    public FlightSqlDataMapping() {
        // Jackson
    }

    public FlightSqlDataMapping(String regex, String rpsClassName, String rpsPropertyName) {
        this.regex = regex;
        this.rpsClassName = rpsClassName;
        this.rpsPropertyName = rpsPropertyName;
    }

    public String getRegex() {
        return regex;
    }

    public void setRegex(String regex) {
        this.regex = regex;
        this.pattern = null;
        this.compiled = false;
    }

    public String getRpsClassName() {
        return rpsClassName;
    }

    public void setRpsClassName(String rpsClassName) {
        this.rpsClassName = rpsClassName;
    }

    public String getRpsPropertyName() {
        return rpsPropertyName;
    }

    public void setRpsPropertyName(String rpsPropertyName) {
        this.rpsPropertyName = rpsPropertyName;
    }

    /**
     * Compile the regex, once. An invalid regex is logged and disables that single mapping rather
     * than failing the whole configuration.
     *
     * @return the compiled pattern, or {@code null} when the mapping is unusable
     */
    public synchronized Pattern getPattern() {
        if (!compiled) {
            compiled = true;
            if (regex == null || regex.isBlank()) {
                LOG.warnf("Ignoring a Flight SQL data mapping without regex (%s/%s).",
                        rpsClassName, rpsPropertyName);
            } else {
                try {
                    pattern = Pattern.compile(regex);
                } catch (Exception e) {
                    LOG.errorf("Ignoring the Flight SQL data mapping with an invalid regex %s: %s",
                            regex, e.getMessage());
                }
            }
        }
        return pattern;
    }

    /**
     * @return true when the mapping can be used: it has a compilable regex and both RPS names
     */
    public boolean isUsable() {
        return getPattern() != null
                && rpsClassName != null && !rpsClassName.isBlank()
                && rpsPropertyName != null && !rpsPropertyName.isBlank();
    }

    @Override
    public String toString() {
        return regex + " -> " + rpsClassName + "/" + rpsPropertyName;
    }
}

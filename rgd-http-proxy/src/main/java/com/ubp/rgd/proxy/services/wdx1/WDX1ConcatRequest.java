package com.ubp.rgd.proxy.services.wdx1;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.ubp.rgd.proxy.utils.JSONFile;

import java.text.ParseException;
import java.text.SimpleDateFormat;

@JsonRootName("wdx1ConcatRequest")
public class WDX1ConcatRequest {
    private String firstName;
    private String lastName;
    private String country;
    private String birthDate;
    private String dateFormat = "yyyy-MM-dd";

    /**
     * Convert a <b>clear</b> date from the format declared by the caller ({@link #getDateFormat()})
     * into the format used by the WDX1 concat configuration.
     * <p>
     * This must only ever be applied to a detokenized value. Applying it to a token used to return
     * the literal string {@code "PARSE ERROR"}, which then travelled silently all the way into the
     * concatenation key; the exception is now propagated so such a mistake fails immediately.
     * <p>
     * Parsing is strict, so an impossible date such as {@code 2024-13-45} is rejected instead of
     * being rolled over into a different, plausible looking date.
     *
     * @param requestDate the clear date, expressed in {@link #getDateFormat()}
     * @param tokenDateFormat the target format
     * @return the date rendered in {@code tokenDateFormat}
     * @throws ParseException if the value is not a valid date in the declared format
     */
    @JsonIgnore
    public String getTokenizationFormattedDate(String requestDate, String tokenDateFormat) throws ParseException {
        SimpleDateFormat requestFormat = new SimpleDateFormat(dateFormat == null ? "yyyy-MM-dd" : dateFormat);
        requestFormat.setLenient(false);
        SimpleDateFormat tokenizationFormat = new SimpleDateFormat(tokenDateFormat);
        return tokenizationFormat.format(requestFormat.parse(requestDate));
    }

    @JsonIgnore
    public String toJSONString() {
        try {
            return JSONFile.serialize(this);
        } catch (JsonProcessingException e) {
            return String.format("{ 'error': '%s'}", e.getMessage());
        }
    }

    @JsonProperty(value = "firstNameToken", required = true)
    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    @JsonProperty(value = "country", required = true)
    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    @JsonProperty(value = "lastNameToken", required = true)
    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    @JsonProperty(value = "birthDateToken", required = true)
    public String getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(String birthDate) {
        this.birthDate = birthDate;
    }

    @JsonProperty(value = "dateFormat")
    public String getDateFormat() { return dateFormat; }

    public void setDateFormat(String dateFormat) {
        this.dateFormat = dateFormat;
    }
}

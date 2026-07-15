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

    @JsonIgnore
    public String getTokenizationFormattedDate(String requestDate, String tokenDateFormat) {
        try {
            SimpleDateFormat requestFormat = new SimpleDateFormat(dateFormat == null ? "yyyy-MM-dd" : dateFormat);
            SimpleDateFormat tokenizationFormat = new SimpleDateFormat(tokenDateFormat);
            return tokenizationFormat.format(requestFormat.parse(requestDate));
        } catch (ParseException e) {
            return "PARSE ERROR";
        }
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

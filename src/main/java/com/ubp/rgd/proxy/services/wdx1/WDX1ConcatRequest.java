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

    @JsonIgnore
    public final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");

    @JsonIgnore
    private final SimpleDateFormat tokenizationFormat = new SimpleDateFormat("yyyy-MM-dd");

    @JsonIgnore
    public String getTokenizationFormattedDate(String requestDate) {
        try {
            return tokenizationFormat.format(dateFormat.parse(requestDate));
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

    @JsonProperty("firstNameToken")
    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    @JsonProperty("country")
    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    @JsonProperty("lastNameToken")
    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    @JsonProperty("birthDateToken")
    public String getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(String birthDate) {
        this.birthDate = birthDate;
    }
}

package com.ubp.rgd.proxy.services.wdx1;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

@JsonRootName("wdx1ConcatResponse")
public class WDX1ConcatResponse {
    private String concatResponseToken = "";

    @JsonProperty("concatToken")
    public String getConcatResponseToken() {
        return concatResponseToken;
    }

    public void setConcatResponseToken(String resp) {
        this.concatResponseToken = resp;
    }

}

package org.apache.cloudstack.wallAlerts.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SilenceStatusDto {
    @JsonProperty("state")
    private String state; // active | pending | expired

    public String getState() {
        return state;
    }

    public void setState(final String state) {
        this.state = state;
    }
}

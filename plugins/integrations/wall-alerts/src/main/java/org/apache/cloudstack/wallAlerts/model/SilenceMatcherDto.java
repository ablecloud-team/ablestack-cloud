package org.apache.cloudstack.wallAlerts.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SilenceMatcherDto {
    @JsonProperty("name")
    private String name;

    @JsonProperty("value")
    private String value;

    @JsonProperty("isRegex")
    private Boolean isRegex; // null이면 false 취급

    @JsonProperty("isEqual")
    private Boolean isEqual; // null이면 true 취급

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public Boolean getIsRegex() {
        return isRegex;
    }

    public Boolean getIsEqual() {
        return isEqual;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public void setValue(final String value) {
        this.value = value;
    }

    public void setIsRegex(final Boolean isRegex) {
        this.isRegex = isRegex;
    }

    public void setIsEqual(final Boolean isEqual) {
        this.isEqual = isEqual;
    }
}

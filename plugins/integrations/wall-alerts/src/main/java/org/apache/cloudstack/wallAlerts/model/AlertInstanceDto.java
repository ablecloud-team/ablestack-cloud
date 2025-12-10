package org.apache.cloudstack.wallAlerts.model;

import java.time.OffsetDateTime;
import java.util.Map;

public class AlertInstanceDto {
    private String fingerprint;
    private String state;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    private Map<String, String> labels;
    private Map<String, String> annotations;

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public OffsetDateTime getStartsAt() { return startsAt; }
    public void setStartsAt(OffsetDateTime startsAt) { this.startsAt = startsAt; }
    public OffsetDateTime getEndsAt() { return endsAt; }
    public void setEndsAt(OffsetDateTime endsAt) { this.endsAt = endsAt; }
    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }
    public Map<String, String> getAnnotations() { return annotations; }
    public void setAnnotations(Map<String, String> annotations) { this.annotations = annotations; }
}

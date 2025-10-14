package org.apache.cloudstack.api.response;

import com.cloud.serializer.Param;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.wallAlerts.client.WallApiClient;

import java.util.List;
import java.util.Map;

public class WallAlertRuleResponse extends BaseResponse {

    @SerializedName("id")              @Param(description = "Rule UID")
    private String id;
    @SerializedName("name")            @Param(description = "Rule title")
    private String name;
    @SerializedName("for")             @Param(description = "Evaluation duration, e.g. 300s")
    private String durationFor;
    @SerializedName("rulegroup")       @Param(description = "Rule group")
    private String ruleGroup;
    @SerializedName("dashboardurl")    @Param(description = "Related dashboard URL")
    private String dashboardUrl;
    @SerializedName("state")           @Param(description = "Aggregated state (ALERTING|PENDING|OK|NODATA)")
    private String state;
    @SerializedName("firingCount")     @Param(description = "Number of firing instances")
    private Integer firingCount;
    @SerializedName("pendingCount")    @Param(description = "Number of pending instances")
    private Integer pendingCount;
    @SerializedName("lastTriggeredAt") @Param(description = "Latest activeAt in KST (yyyy-MM-dd HH:mm)")
    private String lastTriggeredAt;
    @SerializedName("panel")           @Param(description = "Panel id (from annotations.__panelId__)")
    private String panel;
    @SerializedName("kind")            @Param(description = "Kind label (optional)")
    private String kind;
    @SerializedName("operator")        @Param(description = "Operator (optional)")
    private String operator;
    @SerializedName("threshold")       @Param(description = "Threshold value (optional)")
    private Double threshold;
    @SerializedName("threshold2")
    @Param(description = "Upper threshold (for between/outside operators)")
    private Double threshold2;
    @SerializedName("query")           @Param(description = "Query expression")
    private String query;
    @SerializedName("health")          @Param(description = "Rule health (ok|nodata|error)")
    private String health;
    @SerializedName("type")            @Param(description = "Rule type (alerting|recording)")
    private String type;
    @SerializedName("lastEvaluation")  @Param(description = "Last evaluation RFC3339")
    private String lastEvaluation;
    @SerializedName("evaluationTime")  @Param(description = "Evaluation time (sec)")
    private Double evaluationTime;
    @SerializedName("labels")          @Param(description = "Labels")
    private Map<String, String> labels;
    @SerializedName("annotations")     @Param(description = "Annotations")
    private Map<String, String> annotations;
    @SerializedName("isPaused")
    @JsonProperty("isPaused")
    @Param(description = "Whether the rule is paused (grafana_alert.isPaused)")
    private Boolean isPaused;
    @SerializedName("ispaused")
    @Param(description = "Paused state as string for table rendering (Paused|Active)")
    private String ispaused;
    @SerializedName("summary")
    @Param(description = "Summary (HTML)")
    private String summary;
    @SerializedName("description")
    @Param(description = "Description (HTML)")
    private String description;
    @JsonProperty("paused")
    public Boolean paused;
    @JsonProperty("grafana_alert")
    public Alert grafanaAlert;
    @SerializedName("ruleUid")
    @Param(description = "Grafana alert rule UID (__alert_rule_uid__)")
    private String ruleUid;
    @SerializedName("silenced")
    @Param(description = "Whether this rule is currently silenced by Alertmanager")
    private Boolean silenced;


    // ---추가: 인스턴스 상세(도메인/엔드포인트 등 라벨 포함) ---
    @SerializedName("instances")
    @Param(description = "Alert instances from rules API (labels/state/value/activeAt)")
    private List<AlertInstanceResponse> instances;

    public static class AlertInstanceResponse {
        @SerializedName("labels")      public Map<String, String> labels;
        @SerializedName("annotations") public Map<String, String> annotations;
        @SerializedName("state")       public String state;     // Alerting/Pending/Normal/NoData
        @SerializedName("value")       public String value;
        @SerializedName("activeAt")    public String activeAt;  // RFC3339 문자열 그대로
    }

    public WallAlertRuleResponse() { setObjectName("wallalertruleresponse"); }

    public String getQuery() {
        return this.query;
    }
    public String getOperator() { return this.operator; }
    public Double getThreshold() { return this.threshold; }
    public Double getThreshold2() {
        return threshold2;
    }
    public String getPanel() { return this.panel; }
    public java.util.Map<String, String> getLabels() { return this.labels; }
    public java.util.Map<String, String> getAnnotations() { return this.annotations; }
    public java.util.List<AlertInstanceResponse> getInstances() { return this.instances; }
    @JsonProperty("isPaused")
    public Boolean getIsPaused() {
        return isPaused;
    }
    public String getIspaused() { return ispaused; }
    public String getSummary() { return summary; }
    public String getDescription() { return description; }
    public String getRuleUid() { return this.ruleUid; }
    public Boolean getSilenced() { return this.silenced; }

    public static class Alert {
        @com.fasterxml.jackson.annotation.JsonProperty("isPaused")
        @com.fasterxml.jackson.annotation.JsonAlias({ "is_paused", "paused" })
        private Boolean isPaused;

        // 필요하면 getter/setter
        @com.fasterxml.jackson.annotation.JsonProperty("isPaused")
        public Boolean getIsPaused() { return isPaused; }

        @com.fasterxml.jackson.annotation.JsonProperty("isPaused")
        public void setIsPaused(Boolean isPaused) { this.isPaused = isPaused; }
    }

    public static class Rule {
        // 일부 버전은 rule 루트에도 pause가 박힙니다
        @JsonProperty("isPaused")
        public Boolean isPaused;
        @JsonProperty("paused")
        public Boolean paused;
        @JsonProperty("grafana_alert")
        public WallApiClient.RulerRulesResponse.GrafanaAlert alert;
    }

    // --- setters (기존과 동일, 여기에 instances 세터만 추가) ---
    public void setId(final String id) { this.id = id; }
    public void setName(final String name) { this.name = name; }
    public void setDurationFor(final String durationFor) { this.durationFor = durationFor; }
    public void setRuleGroup(final String ruleGroup) { this.ruleGroup = ruleGroup; }
    public void setDashboardUrl(final String dashboardUrl) { this.dashboardUrl = dashboardUrl; }
    public void setState(final String state) { this.state = state; }
    public void setFiringCount(final Integer firingCount) { this.firingCount = firingCount; }
    public void setPendingCount(final Integer pendingCount) { this.pendingCount = pendingCount; }
    public void setLastTriggeredAt(final String lastTriggeredAt) { this.lastTriggeredAt = lastTriggeredAt; }
    public void setPanel(final String panel) { this.panel = panel; }
    public void setKind(final String kind) { this.kind = kind; }
    public void setOperator(final String operator) { this.operator = operator; }
    public void setThreshold(final Double threshold) { this.threshold = threshold; }
    public void setThreshold2(Double threshold2) {
        this.threshold2 = threshold2;
    }
    public void setQuery(final String query) { this.query = query; }
    public void setHealth(final String health) { this.health = health; }
    public void setType(final String type) { this.type = type; }
    public void setLastEvaluation(final String lastEvaluation) { this.lastEvaluation = lastEvaluation; }
    public void setEvaluationTime(final Double evaluationTime) { this.evaluationTime = evaluationTime; }
    public void setLabels(final Map<String, String> labels) { this.labels = labels; }
    public void setAnnotations(final Map<String, String> annotations) { this.annotations = annotations; }
    public void setInstances(final List<AlertInstanceResponse> instances) { this.instances = instances; }
    @JsonProperty("isPaused")
    public void setIsPaused(final Boolean isPaused) {
        this.isPaused = isPaused;
    }
    public void setIspaused(final String ispaused) { this.ispaused = ispaused; }
    public void setSummary(final String summary) { this.summary = summary; }
    public void setDescription(final String description) { this.description = description; }
    public void setRuleUid(final String ruleUid) { this.ruleUid = ruleUid; }
    public void setSilenced(final Boolean silenced) { this.silenced = silenced; }
}



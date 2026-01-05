package org.apache.cloudstack.api.response;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;

import java.util.List;

/**
 * Alertmanager Silence 단건 응답 객체입니다.
 */
public class WallSilenceResponse extends BaseResponse {

    public WallSilenceResponse() {
        setObjectName("silence");
    }

    // --- 기본 식별/상태 ---
    @SerializedName("id")
    @Param(description = "Silence ID")
    private String id;

    @SerializedName("state")
    @Param(description = "Silence state (active, pending, expired)")
    private String state;

    // --- 메타데이터 ---
    @SerializedName("ruleUid")
    @Param(description = "Rule UID from metadata.rule_uid if present")
    private String ruleUid;

    @SerializedName("ruleTitle")
    @Param(description = "Rule title from metadata.rule_title if present")
    private String ruleTitle;

    @SerializedName("folderUid")
    @Param(description = "Folder UID from metadata.folder_uid if present")
    private String folderUid;

    // --- 접근 권한 ---
    @SerializedName("canCreate")
    @Param(description = "Access control: create")
    private Boolean canCreate;

    @SerializedName("canRead")
    @Param(description = "Access control: read")
    private Boolean canRead;

    @SerializedName("canWrite")
    @Param(description = "Access control: write")
    private Boolean canWrite;

    // --- 작성 정보 ---
    @SerializedName("createdBy")
    @Param(description = "Creator")
    private String createdBy;

    @SerializedName("comment")
    @Param(description = "Comment")
    private String comment;

    // --- 시간 정보 ---
    @SerializedName("startsAt")
    @Param(description = "Silence start time")
    private String startsAt;

    @SerializedName("endsAt")
    @Param(description = "Silence end time")
    private String endsAt;

    @SerializedName("createdAt")
    @Param(description = "Silence created time")
    private String createdAt;

    @SerializedName("updatedAt")
    @Param(description = "Silence updated time")
    private String updatedAt;

    // --- 매처 정보 ---
    @SerializedName("matchers")
    @Param(description = "Matchers of the silence")
    private List<SilenceMatcherResponse> matchers;

    @SerializedName("matchersText")
    @Param(description = "Compact matcher string (e.g., 'alertname=value, instance!=host1')")
    private String matchersText;

    // ---------------- Getters/Setters ----------------
    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getState() { return state; }
    public void setState(final String state) { this.state = state; }

    public String getRuleUid() { return ruleUid; }
    public void setRuleUid(final String ruleUid) { this.ruleUid = ruleUid; }

    public String getRuleTitle() { return ruleTitle; }
    public void setRuleTitle(final String ruleTitle) { this.ruleTitle = ruleTitle; }

    public String getFolderUid() { return folderUid; }
    public void setFolderUid(final String folderUid) { this.folderUid = folderUid; }

    public Boolean getCanCreate() { return canCreate; }
    public void setCanCreate(final Boolean canCreate) { this.canCreate = canCreate; }

    public Boolean getCanRead() { return canRead; }
    public void setCanRead(final Boolean canRead) { this.canRead = canRead; }

    public Boolean getCanWrite() { return canWrite; }
    public void setCanWrite(final Boolean canWrite) { this.canWrite = canWrite; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(final String createdBy) { this.createdBy = createdBy; }

    public String getComment() { return comment; }
    public void setComment(final String comment) { this.comment = comment; }

    public String getStartsAt() { return startsAt; }
    public void setStartsAt(final String startsAt) { this.startsAt = startsAt; }

    public String getEndsAt() { return endsAt; }
    public void setEndsAt(final String endsAt) { this.endsAt = endsAt; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(final String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(final String updatedAt) { this.updatedAt = updatedAt; }

    public List<SilenceMatcherResponse> getMatchers() { return matchers; }
    public void setMatchers(final List<SilenceMatcherResponse> matchers) { this.matchers = matchers; }

    public String getMatchersText() { return matchersText; }
    public void setMatchersText(final String matchersText) { this.matchersText = matchersText; }

    // ---------------- Inner: SilenceMatcherResponse ----------------
    public static class SilenceMatcherResponse extends BaseResponse {
        @SerializedName("name")
        @Param(description = "Label name")
        private String name;

        @SerializedName("value")
        @Param(description = "Label value")
        private String value;

        @SerializedName("isRegex")
        @Param(description = "Whether value is a regex")
        private Boolean isRegex;

        @SerializedName("isEqual")
        @Param(description = "Equal(true) or NotEqual(false). If null, treated as true")
        private Boolean isEqual;

        public String getName() { return name; }
        public void setName(final String name) { this.name = name; }

        public String getValue() { return value; }
        public void setValue(final String value) { this.value = value; }

        public Boolean getIsRegex() { return isRegex; }
        public void setIsRegex(final Boolean isRegex) { this.isRegex = isRegex; }

        public Boolean getIsEqual() { return isEqual; }
        public void setIsEqual(final Boolean isEqual) { this.isEqual = isEqual; }
    }
}

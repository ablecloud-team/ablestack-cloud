package org.apache.cloudstack.wallAlerts.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

public class SilenceDto {
    @JsonProperty("id")
    private String id;

    @JsonProperty("status")
    private SilenceStatusDto status;

    @JsonProperty("matchers")
    private List<SilenceMatcherDto> matchers;

    @JsonProperty("createdBy")
    private String createdBy;

    @JsonProperty("comment")
    private String comment;

    @JsonProperty("startsAt")
    private OffsetDateTime startsAt;

    @JsonProperty("endsAt")
    private OffsetDateTime endsAt;

    @JsonProperty("createdAt")
    private OffsetDateTime createdAt;

    @JsonProperty("updatedAt")
    private OffsetDateTime updatedAt;

    // ✅ 새 파일 없이 내부 클래스로 메타/권한 수용
    @JsonProperty("metadata")
    private Metadata metadata;

    @JsonProperty("accessControl")
    private AccessControl accessControl;

    // ---------- Getters ----------
    public String getId() {
        return id;
    }

    public SilenceStatusDto getStatus() {
        return status;
    }

    public List<SilenceMatcherDto> getMatchers() {
        return matchers;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getComment() {
        return comment;
    }

    public OffsetDateTime getStartsAt() {
        return startsAt;
    }

    public OffsetDateTime getEndsAt() {
        return endsAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public AccessControl getAccessControl() {
        return accessControl;
    }

    // ---------- Setters ----------
    public void setId(final String id) {
        this.id = id;
    }

    public void setStatus(final SilenceStatusDto status) {
        this.status = status;
    }

    public void setMatchers(final List<SilenceMatcherDto> matchers) {
        this.matchers = matchers;
    }

    public void setCreatedBy(final String createdBy) {
        this.createdBy = createdBy;
    }

    public void setComment(final String comment) {
        this.comment = comment;
    }

    public void setStartsAt(final OffsetDateTime startsAt) {
        this.startsAt = startsAt;
    }

    public void setEndsAt(final OffsetDateTime endsAt) {
        this.endsAt = endsAt;
    }

    public void setCreatedAt(final OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(final OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setMetadata(final Metadata metadata) {
        this.metadata = metadata;
    }

    public void setAccessControl(final AccessControl accessControl) {
        this.accessControl = accessControl;
    }

    // ---------- 내부 클래스: 메타데이터 ----------
    public static class Metadata {
        @JsonProperty("rule_uid")
        private String ruleUid;

        @JsonProperty("rule_title")
        private String ruleTitle;

        @JsonProperty("folder_uid")
        private String folderUid;

        public String getRuleUid() {
            return ruleUid;
        }

        public String getRuleTitle() {
            return ruleTitle;
        }

        public String getFolderUid() {
            return folderUid;
        }

        public void setRuleUid(final String ruleUid) {
            this.ruleUid = ruleUid;
        }

        public void setRuleTitle(final String ruleTitle) {
            this.ruleTitle = ruleTitle;
        }

        public void setFolderUid(final String folderUid) {
            this.folderUid = folderUid;
        }
    }

    // ---------- 내부 클래스: 접근 권한 ----------
    public static class AccessControl {
        @JsonProperty("create")
        private Boolean create;

        @JsonProperty("read")
        private Boolean read;

        @JsonProperty("write")
        private Boolean write;

        public Boolean getCreate() {
            return create;
        }

        public Boolean getRead() {
            return read;
        }

        public Boolean getWrite() {
            return write;
        }

        public void setCreate(final Boolean create) {
            this.create = create;
        }

        public void setRead(final Boolean read) {
            this.read = read;
        }

        public void setWrite(final Boolean write) {
            this.write = write;
        }
    }
}

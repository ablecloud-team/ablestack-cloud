// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.api.response.dr;

import java.util.Date;

import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class DrVmPlanAssociationResponse extends BaseResponse {
    @SerializedName("planid") @Param(description = "the DR plan ID") private String planId;
    @SerializedName("planname") @Param(description = "the DR plan name") private String planName;
    @SerializedName("relationshiprole") @Param(description = "the stable VM relationship role") private String relationshipRole;
    @SerializedName("authorityrole") @Param(description = "the current workload authority role") private String authorityRole;
    @SerializedName("planstate") @Param(description = "the persisted DR plan state") private String planState;
    @SerializedName("adminstate") @Param(description = "the DR plan administrative state") private String adminState;
    @SerializedName("direction") @Param(description = "the DR direction") private String direction;
    @SerializedName("sourcesiteid") @Param(description = "the source site ID") private String sourceSiteId;
    @SerializedName("sourcesitename") @Param(description = "the source site name") private String sourceSiteName;
    @SerializedName("sourcevmid") @Param(description = "the persisted source VM UUID or external reference") private String sourceVmId;
    @SerializedName("sourcevmname") @Param(description = "the persisted source VM name") private String sourceVmName;
    @SerializedName("sourcevmstate") @Param(description = "the locally persisted source VM state") private String sourceVmState;
    @SerializedName("targetsiteid") @Param(description = "the target site ID") private String targetSiteId;
    @SerializedName("targetsitename") @Param(description = "the target site name") private String targetSiteName;
    @SerializedName("targetvmid") @Param(description = "the persisted target VM UUID or external reference") private String targetVmId;
    @SerializedName("targetvmname") @Param(description = "the persisted target VM name") private String targetVmName;
    @SerializedName("targetvmstate") @Param(description = "the locally persisted target VM state") private String targetVmState;
    @SerializedName("targetmaterializationstate") @Param(description = "the target materialization state") private String targetMaterializationState;
    @SerializedName("targetpowerstate") @Param(description = "the persisted target power state") private String targetPowerState;
    @SerializedName("protectionstate") @Param(description = "the persisted protection state") private String protectionState;
    @SerializedName("freshnessstate") @Param(description = "the persisted RPO freshness state") private String freshnessState;
    @SerializedName("replicationactivity") @Param(description = "the persisted replication activity") private String replicationActivity;
    @SerializedName("rposeconds") @Param(description = "the configured RPO seconds") private Integer rpoSeconds;
    @SerializedName("rpoageseconds") @Param(description = "the persisted RPO age seconds") private Long rpoAgeSeconds;
    @SerializedName("lasttargetdurableat") @Param(description = "the last durable target time") private Date lastTargetDurableAt;
    @SerializedName("projectionstate") @Param(description = "the local projection availability") private String projectionState;
    @SerializedName("dataupdatedat") @Param(description = "the last persisted observation time") private Date dataUpdatedAt;
    @SerializedName("latestrunid") @Param(description = "the latest operation ID") private String latestRunId;
    @SerializedName("latestruntype") @Param(description = "the latest operation type") private String latestRunType;
    @SerializedName("latestrunstate") @Param(description = "the latest operation state") private String latestRunState;

    public DrVmPlanAssociationResponse() { setObjectName("drvmplanassociation"); }
    public String getPlanId() { return planId; }
    public String getRelationshipRole() { return relationshipRole; }
    public String getAuthorityRole() { return authorityRole; }
    public String getSourceVmId() { return sourceVmId; }
    public String getSourceVmName() { return sourceVmName; }
    public String getTargetVmId() { return targetVmId; }
    public String getTargetVmName() { return targetVmName; }
    public String getProjectionState() { return projectionState; }
    public void setPlanId(String value) { planId = value; }
    public void setPlanName(String value) { planName = value; }
    public void setRelationshipRole(String value) { relationshipRole = value; }
    public void setAuthorityRole(String value) { authorityRole = value; }
    public void setPlanState(String value) { planState = value; }
    public void setAdminState(String value) { adminState = value; }
    public void setDirection(String value) { direction = value; }
    public void setSourceSiteId(String value) { sourceSiteId = value; }
    public void setSourceSiteName(String value) { sourceSiteName = value; }
    public void setSourceVmId(String value) { sourceVmId = value; }
    public void setSourceVmName(String value) { sourceVmName = value; }
    public void setSourceVmState(String value) { sourceVmState = value; }
    public void setTargetSiteId(String value) { targetSiteId = value; }
    public void setTargetSiteName(String value) { targetSiteName = value; }
    public void setTargetVmId(String value) { targetVmId = value; }
    public void setTargetVmName(String value) { targetVmName = value; }
    public void setTargetVmState(String value) { targetVmState = value; }
    public void setTargetMaterializationState(String value) { targetMaterializationState = value; }
    public void setTargetPowerState(String value) { targetPowerState = value; }
    public void setProtectionState(String value) { protectionState = value; }
    public void setFreshnessState(String value) { freshnessState = value; }
    public void setReplicationActivity(String value) { replicationActivity = value; }
    public void setRpoSeconds(Integer value) { rpoSeconds = value; }
    public void setRpoAgeSeconds(Long value) { rpoAgeSeconds = value; }
    public void setLastTargetDurableAt(Date value) { lastTargetDurableAt = value; }
    public void setProjectionState(String value) { projectionState = value; }
    public void setDataUpdatedAt(Date value) { dataUpdatedAt = value; }
    public void setLatestRunId(String value) { latestRunId = value; }
    public void setLatestRunType(String value) { latestRunType = value; }
    public void setLatestRunState(String value) { latestRunState = value; }
}

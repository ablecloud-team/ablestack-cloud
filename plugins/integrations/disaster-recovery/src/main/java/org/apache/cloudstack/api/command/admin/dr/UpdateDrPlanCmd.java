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
package org.apache.cloudstack.api.command.admin.dr;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.dr.DrPlanMutationResponse;
import org.apache.cloudstack.api.response.dr.DrPlanResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.dr.DrPlanGuidedSpec;
import com.cloud.dr.DrPlanGuidedSpecBuilder;
import com.cloud.dr.DrPlanReadiness;
import com.cloud.dr.DrPlanReadinessValidator;
import com.cloud.dr.DrPlanService;
import com.cloud.dr.DrPlanVO;
import com.cloud.dr.cluster.DisasterRecoveryClusterEventTypes;

@APICommand(name = UpdateDrPlanCmd.APINAME,
        description = "Update a Cross Hypervisor DR plan",
        responseObject = DrPlanMutationResponse.class,
        authorized = {RoleType.Admin})
public class UpdateDrPlanCmd extends BaseAsyncCmd {
    public static final String APINAME = "updateDrPlan";

    @Inject
    private DrPlanService drPlanService;
    @Inject
    private DrPlanReadinessValidator drPlanReadinessValidator;
    @Inject
    private DrPlanGuidedSpecBuilder drPlanGuidedSpecBuilder;

    @Parameter(name = "id", type = CommandType.UUID, entityType = DrPlanResponse.class, required = true, description = "the DR plan ID")
    private Long id;

    @Parameter(name = "name", type = CommandType.STRING, description = "the DR plan name")
    private String name;

    @Parameter(name = "description", type = CommandType.STRING, description = "the DR plan description")
    private String description;

    @Parameter(name = "sourcevmid", type = CommandType.UUID, entityType = UserVmResponse.class, description = "the source VM ID")
    private Long sourceVmId;

    @Parameter(name = "sourceexternalref", type = CommandType.STRING, description = "the source external reference")
    private String sourceExternalRef;

    @Parameter(name = "enginetype", type = CommandType.STRING, description = "the engine type")
    private String engineType;

    @Parameter(name = "enginebindingtype", type = CommandType.STRING, description = "the engine binding type")
    private String engineBindingType;

    @Parameter(name = "enginebindingid", type = CommandType.LONG, description = "the engine binding ID")
    private Long engineBindingId;

    @Parameter(name = "rposeconds", type = CommandType.INTEGER, description = "the target RPO in seconds")
    private Integer rpoSeconds;

    @Parameter(name = "rtoseconds", type = CommandType.INTEGER, description = "the target RTO in seconds")
    private Integer rtoSeconds;

    @Parameter(name = "schedulejson", type = CommandType.STRING, length = 65535, description = "the sync schedule JSON")
    private String scheduleJson;

    @Parameter(name = "policyjson", type = CommandType.STRING, length = 65535, description = "the plan policy JSON")
    private String policyJson;

    @Parameter(name = "mappingjson", type = CommandType.STRING, length = 65535, description = "the plan mapping JSON")
    private String mappingJson;

    @Parameter(name = "quiescepolicyjson", type = CommandType.STRING, length = 65535, description = "the quiesce policy JSON")
    private String quiescePolicyJson;

    @Parameter(name = "sourceworkerhostid", type = CommandType.UUID, entityType = HostResponse.class,
            description = "the source FTCTL_DR worker host ID")
    private Long sourceWorkerHostId;

    @Parameter(name = "targetworkerhostid", type = CommandType.UUID, entityType = HostResponse.class,
            description = "the target FTCTL_DR worker host ID")
    private Long targetWorkerHostId;

    @Parameter(name = "coordinatorworkerhostid", type = CommandType.UUID, entityType = HostResponse.class,
            description = "the coordinator FTCTL_DR worker host ID")
    private Long coordinatorWorkerHostId;

    @Parameter(name = "guidedplan", type = CommandType.BOOLEAN, description = "whether guided inputs should generate engine JSON")
    private Boolean guidedPlan;

    @Parameter(name = "allowdraft", type = CommandType.BOOLEAN, description = "whether to allow saving an execution-incomplete draft plan")
    private Boolean allowDraft;

    @Parameter(name = "targetvmname", type = CommandType.STRING, description = "the recovery target VM name")
    private String targetVmName;

    @Parameter(name = "targetzoneid", type = CommandType.STRING, description = "the recovery target Cloud zone reference")
    private String targetZoneId;

    @Parameter(name = "targetstorageref", type = CommandType.STRING, description = "the default recovery target storage reference; disk mappings override it per disk")
    private String targetStorageRef;

    @Parameter(name = "targetcomputeref", type = CommandType.STRING, description = "the recovery target compute reference")
    private String targetComputeRef;

    @Parameter(name = "targetcpunumber", type = CommandType.INTEGER, description = "the recovery target CPU core count for dynamic compute offerings")
    private Integer targetCpuNumber;

    @Parameter(name = "targetcpuspeed", type = CommandType.INTEGER, description = "the recovery target CPU speed in MHz for dynamic compute offerings")
    private Integer targetCpuSpeed;

    @Parameter(name = "targetmemory", type = CommandType.INTEGER, description = "the recovery target memory in MiB for dynamic compute offerings")
    private Integer targetMemory;

    @Parameter(name = "targetboottype", type = CommandType.STRING, description = "the recovery target boot type, BIOS or UEFI")
    private String targetBootType;

    @Parameter(name = "targetbootmode", type = CommandType.STRING, description = "the recovery target boot mode, LEGACY or SECURE")
    private String targetBootMode;

    @Parameter(name = "targetrootdiskcontroller", type = CommandType.STRING, description = "the recovery target root disk controller")
    private String targetRootDiskController;

    @Parameter(name = "targetdatadiskcontroller", type = CommandType.STRING, description = "the recovery target data disk controller")
    private String targetDataDiskController;

    @Parameter(name = "targetiothreadsenabled", type = CommandType.BOOLEAN, description = "whether to enable KVM IOThreads on the recovery target VM")
    private Boolean targetIoThreadsEnabled;

    @Parameter(name = "targetiopolicy", type = CommandType.STRING, description = "the recovery target KVM IO policy; defaults to io_uring")
    private String targetIoPolicy;

    @Parameter(name = "targetnetworkref", type = CommandType.STRING, description = "the recovery target network reference")
    private String targetNetworkRef;

    @Parameter(name = "targetfolderpath", type = CommandType.STRING, description = "the VMware target folder path")
    private String targetFolderPath;

    @Parameter(name = "diskmappingsjson", type = CommandType.STRING, length = 65535, description = "the compact guided disk mapping JSON array; per-disk target storage is authoritative")
    private String diskMappingsJson;

    @Parameter(name = "consistencymode", type = CommandType.STRING, description = "the consistency mode")
    private String consistencyMode;

    @Parameter(name = "testnetworkmode", type = CommandType.STRING, description = "the test failover network mode")
    private String testNetworkMode;

    @Parameter(name = "testbootvalidationmode", type = CommandType.STRING, description = "test failover boot validation mode: POWER_STATE_ONLY or QGA_REQUIRED")
    private String testBootValidationMode;

    @Parameter(name = "testboottimeoutseconds", type = CommandType.INTEGER, description = "test failover boot validation timeout in seconds")
    private Integer testBootTimeoutSeconds;

    @Parameter(name = "failoverpoweron", type = CommandType.BOOLEAN, description = "whether failover should power on the target VM")
    private Boolean failoverPowerOn;

    @Parameter(name = "syncintervalseconds", type = CommandType.INTEGER, description = "the continuous sync interval in seconds")
    private Integer syncIntervalSeconds;

    @Parameter(name = "retentioncount", type = CommandType.INTEGER, description = "the restore point retention count")
    private Integer retentionCount;

    @Parameter(name = "bandwidthlimitmbps", type = CommandType.INTEGER, description = "the optional bandwidth limit in Mbps")
    private Integer bandwidthLimitMbps;

    @Parameter(name = "retrycount", type = CommandType.INTEGER, description = "the retry count")
    private Integer retryCount;

    @Override
    public void execute() throws ServerApiException {
        try {
            DrPlanVO update = new DrPlanVO(name, 0L, 0L, "UPDATE");
            update.setDescription(description);
            update.setSourceVmId(sourceVmId);
            update.setSourceExternalRef(sourceExternalRef);
            update.setEngineType(engineType);
            update.setEngineBindingType(engineBindingType);
            update.setEngineBindingId(engineBindingId);
            update.setRpoSeconds(rpoSeconds);
            update.setRtoSeconds(rtoSeconds);
            update.setScheduleJson(scheduleJson);
            update.setPolicyJson(policyJson);
            update.setMappingJson(mappingJson);
            update.setQuiescePolicyJson(quiescePolicyJson);
            update.setSourceWorkerHostId(sourceWorkerHostId);
            update.setTargetWorkerHostId(targetWorkerHostId);
            update.setCoordinatorWorkerHostId(coordinatorWorkerHostId);
            applyGuidedSpec(update);
            validateDraftPolicy(update);
            DrPlanVO plan = drPlanService.updatePlan(id, update);
            DrPlanMutationResponse response = new DrPlanMutationResponse();
            response.setId(plan.getUuid());
            response.setName(plan.getName());
            response.setState(plan.getState());
            response.setOperation("UPDATE");
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (ServerApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + RESPONSE_SUFFIX;
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }

    @Override
    public String getEventType() {
        return DisasterRecoveryClusterEventTypes.EVENT_DR_PLAN_UPDATE;
    }

    @Override
    public String getEventDescription() {
        return "Updating DR plan " + id;
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.DisasterRecoveryCluster;
    }

    @Override
    public Long getApiResourceId() {
        return id;
    }

    private DrPlanGuidedSpec buildGuidedSpec() {
        DrPlanGuidedSpec spec = new DrPlanGuidedSpec();
        spec.setGuidedPlan(guidedPlan);
        spec.setTargetVmName(targetVmName);
        spec.setTargetZoneId(targetZoneId);
        spec.setTargetStorageRef(targetStorageRef);
        spec.setTargetComputeRef(targetComputeRef);
        spec.setTargetCpuNumber(targetCpuNumber);
        spec.setTargetCpuSpeed(targetCpuSpeed);
        spec.setTargetMemory(targetMemory);
        spec.setTargetBootType(targetBootType);
        spec.setTargetBootMode(targetBootMode);
        spec.setTargetRootDiskController(targetRootDiskController);
        spec.setTargetDataDiskController(targetDataDiskController);
        spec.setTargetIoThreadsEnabled(targetIoThreadsEnabled);
        spec.setTargetIoPolicy(targetIoPolicy);
        spec.setTargetNetworkRef(targetNetworkRef);
        spec.setTargetFolderPath(targetFolderPath);
        spec.setDiskMappingsJson(diskMappingsJson);
        spec.setConsistencyMode(consistencyMode);
        spec.setTestNetworkMode(testNetworkMode);
        spec.setTestBootValidationMode(testBootValidationMode);
        spec.setTestBootTimeoutSeconds(testBootTimeoutSeconds);
        spec.setFailoverPowerOn(failoverPowerOn);
        spec.setSyncIntervalSeconds(syncIntervalSeconds);
        spec.setRetentionCount(retentionCount);
        spec.setBandwidthLimitMbps(bandwidthLimitMbps);
        spec.setRetryCount(retryCount);
        return spec;
    }

    private void applyGuidedSpec(DrPlanVO update) {
        DrPlanGuidedSpec spec = buildGuidedSpec();
        if (!spec.shouldApply()) {
            return;
        }
        DrPlanVO current = drPlanService.getPlan(id);
        spec = guidedSpecBuilder().mergeForUpdate(current, spec);
        DrPlanVO merged = new DrPlanVO(current.getName(), current.getSourceSiteId(), current.getTargetSiteId(), current.getDirection());
        merged.setSourceVmId(current.getSourceVmId());
        merged.setSourceExternalRef(current.getSourceExternalRef());
        merged.setRpoSeconds(update.getRpoSeconds() != null ? update.getRpoSeconds() : current.getRpoSeconds());
        merged.setRtoSeconds(update.getRtoSeconds() != null ? update.getRtoSeconds() : current.getRtoSeconds());
        merged.setSourceWorkerHostId(update.getSourceWorkerHostId() != null ? update.getSourceWorkerHostId() : current.getSourceWorkerHostId());
        merged.setTargetWorkerHostId(update.getTargetWorkerHostId() != null ? update.getTargetWorkerHostId() : current.getTargetWorkerHostId());
        merged.setCoordinatorWorkerHostId(update.getCoordinatorWorkerHostId() != null ? update.getCoordinatorWorkerHostId() : current.getCoordinatorWorkerHostId());
        guidedSpecBuilder().applyIfRequested(merged, spec);
        update.setMappingJson(guidedSpecBuilder().preserveUnchangedJson(current.getMappingJson(), merged.getMappingJson()));
        update.setScheduleJson(guidedSpecBuilder().preserveUnchangedJson(current.getScheduleJson(), merged.getScheduleJson()));
        update.setPolicyJson(guidedSpecBuilder().preserveUnchangedJson(current.getPolicyJson(), merged.getPolicyJson()));
        update.setQuiescePolicyJson(guidedSpecBuilder().preserveUnchangedJson(current.getQuiescePolicyJson(), merged.getQuiescePolicyJson()));
    }

    private void validateDraftPolicy(DrPlanVO update) {
        if (drPlanReadinessValidator == null || allowDraft == null || Boolean.TRUE.equals(allowDraft)) {
            return;
        }
        DrPlanVO current = drPlanService.getPlan(id);
        DrPlanVO merged = new DrPlanVO(
                update.getName() != null ? update.getName() : current.getName(),
                current.getSourceSiteId(),
                current.getTargetSiteId(),
                current.getDirection());
        merged.setSourceVmId(update.getSourceVmId() != null ? update.getSourceVmId() : current.getSourceVmId());
        merged.setSourceExternalRef(update.getSourceExternalRef() != null ? update.getSourceExternalRef() : current.getSourceExternalRef());
        merged.setEngineType(update.getEngineType() != null ? update.getEngineType() : current.getEngineType());
        merged.setEngineBindingType(update.getEngineBindingType() != null ? update.getEngineBindingType() : current.getEngineBindingType());
        merged.setMappingJson(update.getMappingJson() != null ? update.getMappingJson() : current.getMappingJson());
        merged.setSourceWorkerHostId(update.getSourceWorkerHostId() != null ? update.getSourceWorkerHostId() : current.getSourceWorkerHostId());
        merged.setTargetWorkerHostId(update.getTargetWorkerHostId() != null ? update.getTargetWorkerHostId() : current.getTargetWorkerHostId());
        merged.setCoordinatorWorkerHostId(update.getCoordinatorWorkerHostId() != null ? update.getCoordinatorWorkerHostId() : current.getCoordinatorWorkerHostId());
        DrPlanReadiness readiness = drPlanReadinessValidator.validateForExecution(merged);
        if (!readiness.isExecutionReady()) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR,
                    "DR plan is not execution-ready: " + readiness.getBlockingReasons());
        }
    }

    private DrPlanGuidedSpecBuilder guidedSpecBuilder() {
        return drPlanGuidedSpecBuilder != null ? drPlanGuidedSpecBuilder : new DrPlanGuidedSpecBuilder();
    }
}

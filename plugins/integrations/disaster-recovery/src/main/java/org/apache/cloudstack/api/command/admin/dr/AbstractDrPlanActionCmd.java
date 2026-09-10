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

import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.dr.DrPlanResponse;
import org.apache.cloudstack.api.response.dr.DrReplicaResponse;
import org.apache.cloudstack.api.response.dr.DrRestorePointResponse;
import org.apache.cloudstack.api.response.dr.DrRunResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.dr.DrActionAvailability;
import com.cloud.dr.DrConstants;
import com.cloud.dr.DrPlanService;
import com.cloud.dr.DrRunVO;
import com.cloud.dr.DrRunService;
import com.cloud.dr.response.DrResponseGenerator;

public abstract class AbstractDrPlanActionCmd extends BaseAsyncCmd {
    @Inject
    protected DrRunService drRunService;
    @Inject
    protected DrPlanService drPlanService;
    @Inject
    protected DrResponseGenerator drResponseGenerator;

    @Parameter(name = "planid", type = CommandType.UUID, entityType = DrPlanResponse.class,
            required = true, description = "the DR plan ID")
    private Long planId;

    @Parameter(name = "restorepointid", type = CommandType.UUID, entityType = DrRestorePointResponse.class,
            description = "the DR restore point ID")
    private Long restorePointId;

    @Parameter(name = "replicaid", type = CommandType.UUID, entityType = DrReplicaResponse.class,
            description = "the DR replica ID")
    private Long replicaId;

    @Parameter(name = "idempotencykey", type = CommandType.STRING,
            description = "the idempotency key for retry-safe action execution")
    private String idempotencyKey;

    @Parameter(name = "actionintent", type = CommandType.STRING,
            description = "the immutable DR run type intended by the caller")
    private String actionIntent;

    @Parameter(name = "dryrun", type = CommandType.BOOLEAN,
            description = "whether to run validation only")
    private Boolean dryRun;

    @Parameter(name = "force", type = CommandType.BOOLEAN,
            description = "whether to force a dangerous action")
    private Boolean force;

    @Parameter(name = "acknowledgement", type = CommandType.STRING,
            description = "the acknowledgement phrase for dangerous actions")
    private String acknowledgement;

    @Parameter(name = "reason", type = CommandType.STRING,
            description = "the operator supplied action reason")
    private String reason;

    public Long getPlanId() {
        return planId;
    }

    public Long getRestorePointId() {
        return restorePointId;
    }

    public Long getReplicaId() {
        return replicaId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getActionIntent() {
        return actionIntent;
    }

    public Boolean getDryRun() {
        return dryRun;
    }

    public Boolean getForce() {
        return force;
    }

    public String getAcknowledgement() {
        return acknowledgement;
    }

    public String getReason() {
        return reason;
    }

    protected abstract String getRunType();

    @Override
    public String getCommandName() {
        return getApiName().toLowerCase() + RESPONSE_SUFFIX;
    }

    protected abstract String getApiName();

    @Override
    public void execute() throws ServerApiException {
        validateActionIntent();
        validateActionAllowed();
        try {
            DrRunVO run = drRunService.startRun(planId, getRunType(), idempotencyKey, CallContext.current().getCallingUserId(), null, buildRequestJson());
            DrRunResponse response = drResponseGenerator.createRunResponse(run, drRunService.listRunSteps(run.getId()), true);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (ServerApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    protected void validateActionIntent() {
        if (StringUtils.isNotBlank(actionIntent)
                && !StringUtils.equalsIgnoreCase(actionIntent, getRunType())) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR,
                    DrConstants.ERROR_ACTION_INTENT_MISMATCH + ": expected " + getRunType());
        }
    }

    protected boolean validatesCapabilitiesAtTargetDispatch() {
        return false;
    }

    protected Map<String, DrActionAvailability> actionAvailabilityForValidation() {
        // Target-only recovery validates the live target capability in its adapter.
        // Do not probe unrelated source capabilities before dispatching that action.
        return validatesCapabilitiesAtTargetDispatch()
                ? drPlanService.getDatabaseActionEvaluation(planId).getAvailability()
                : drPlanService.getActionAvailability(planId);
    }

    protected void validateActionAllowed() {
        String actionKey = getActionEligibilityKey();
        Map<String, DrActionAvailability> availability = actionAvailabilityForValidation();
        if (availability == null || !availability.containsKey(actionKey)) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR,
                    String.format("DR action %s is not supported by the current plan engine", getApiName()));
        }
        DrActionAvailability actionAvailability = availability.get(actionKey);
        if (actionAvailability == null || !actionAvailability.isEnabled()) {
            String reason = actionAvailability != null ? actionAvailability.getReasonCode() : null;
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR,
                    String.format("DR action %s is not allowed in the current plan state%s", getApiName(),
                            StringUtils.isNotBlank(reason) ? ": " + reason : ""));
        }
    }

    protected String getActionEligibilityKey() {
        String runType = getRunType() == null ? "" : getRunType().toUpperCase(Locale.ROOT);
        switch (runType) {
            case DrConstants.RUN_TYPE_SYNC:
                return "sync";
            case DrConstants.RUN_TYPE_RECOVER_SYNC:
                return "recoverSync";
            case DrConstants.RUN_TYPE_PAUSE_SYNC:
                return "pauseSync";
            case DrConstants.RUN_TYPE_RESUME_SYNC:
                return "resumeSync";
            case DrConstants.RUN_TYPE_TEST_FAILOVER:
                return "testFailover";
            case DrConstants.RUN_TYPE_TEST_CLEANUP:
                return "stopTestFailover";
            case DrConstants.RUN_TYPE_FAILOVER:
                return "failover";
            case DrConstants.RUN_TYPE_FENCE_CONFIRM:
                return "confirmFenceClear";
            case DrConstants.RUN_TYPE_FAILBACK:
                return "failback";
            case DrConstants.RUN_TYPE_REPROTECT:
                return "reprotect";
            case DrConstants.RUN_TYPE_ADOPT:
                return "adoptReplica";
            case DrConstants.RUN_TYPE_RELEASE:
                return "releaseProtection";
            default:
                return runType.toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.DisasterRecoveryCluster;
    }

    @Override
    public Long getApiResourceId() {
        return planId;
    }

    protected String buildRequestJson() {
        JsonObject request = new JsonObject();
        addProperty(request, "restorePointId", restorePointId);
        addProperty(request, "replicaId", replicaId);
        addProperty(request, "dryRun", dryRun);
        addProperty(request, "force", force);
        addProperty(request, "acknowledgement", acknowledgement);
        addProperty(request, "reason", reason);
        addProperty(request, "actionIntent", StringUtils.defaultIfBlank(actionIntent, getRunType()));
        addProperty(request, "apiCommand", getApiName());
        addRequestProperties(request);
        return request.entrySet().isEmpty() ? null : request.toString();
    }

    protected void addRequestProperties(JsonObject request) {
    }

    protected void addProperty(JsonObject request, String name, String value) {
        if (value != null) {
            request.addProperty(name, value);
        }
    }

    protected void addProperty(JsonObject request, String name, Number value) {
        if (value != null) {
            request.addProperty(name, value);
        }
    }

    protected void addProperty(JsonObject request, String name, Boolean value) {
        if (value != null) {
            request.addProperty(name, value);
        }
    }
}

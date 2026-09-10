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

import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.dr.DrRunResponse;
import org.apache.commons.lang3.StringUtils;

import com.cloud.dr.DrActionAvailability;
import com.cloud.dr.DrConstants;
import com.cloud.dr.DrRestorePointVO;
import com.cloud.dr.cluster.DisasterRecoveryClusterEventTypes;
import com.cloud.dr.dao.DrRestorePointDao;
import com.google.gson.JsonObject;

@APICommand(name = StartDrFailoverCmd.APINAME, description = "Start a DR failover run", responseObject = DrRunResponse.class,
        responseView = ResponseObject.ResponseView.Full, authorized = {RoleType.Admin})
public class StartDrFailoverCmd extends AbstractDrPlanActionCmd {
    public static final String APINAME = "startDrFailover";

    @Inject
    private DrRestorePointDao drRestorePointDao;

    @Parameter(name = "disaster", type = CommandType.BOOLEAN, description = "whether this is a disaster failover")
    private Boolean disaster;

    @Parameter(name = "finalsync", type = CommandType.BOOLEAN,
            description = "whether planned failover should request one final checkpoint before target promotion")
    private Boolean finalSync;

    @Parameter(name = "skipsourcefencerequest", type = CommandType.BOOLEAN, description = "whether to skip source fence request")
    private Boolean skipSourceFenceRequest;

    @Parameter(name = "sourceisolationacknowledged", type = CommandType.BOOLEAN,
            description = "operator acknowledgement that the source is isolated or unreachable in disaster mode")
    private Boolean sourceIsolationAcknowledged;

    @Parameter(name = "sourceisolationreason", type = CommandType.STRING,
            description = "operator reason or evidence for source isolation in disaster mode")
    private String sourceIsolationReason;

    public Boolean getDisaster() {
        return disaster;
    }

    public Boolean getFinalSync() {
        return finalSync;
    }

    public Boolean getSkipSourceFenceRequest() {
        return skipSourceFenceRequest;
    }

    public Boolean getSourceIsolationAcknowledged() {
        return sourceIsolationAcknowledged;
    }

    public String getSourceIsolationReason() {
        return sourceIsolationReason;
    }

    @Override
    protected boolean validatesCapabilitiesAtTargetDispatch() {
        return Boolean.TRUE.equals(disaster);
    }

    @Override
    protected void validateActionAllowed() {
        if (Boolean.TRUE.equals(disaster)) {
            Map<String, DrActionAvailability> availability = actionAvailabilityForValidation();
            DrActionAvailability failover = availability != null ? availability.get(getActionEligibilityKey()) : null;
            if (failover != null && failover.isApplicable() && !failover.isEnabled()
                    && DrConstants.ACTION_REASON_CUTOVER_NOT_READY.equals(failover.getReasonCode())
                    && Boolean.TRUE.equals(drPlanService.getDatabaseActionEvaluation(getPlanId()).getEligibility().get("disasterFailover"))) {
                return;
            }
        }
        super.validateActionAllowed();
    }

    @Override
    protected String getRunType() {
        return "FAILOVER";
    }

    @Override
    protected void addRequestProperties(JsonObject request) {
        boolean disasterFailover = Boolean.TRUE.equals(disaster);
        if (disasterFailover && (!Boolean.TRUE.equals(sourceIsolationAcknowledged)
                || StringUtils.isBlank(sourceIsolationReason))) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR,
                    "Disaster failover requires source isolation acknowledgement and a reason");
        }
        DrRestorePointVO restorePoint = resolveRestorePoint();
        addProperty(request, "disaster", disaster);
        addProperty(request, "mode", disasterFailover ? "disaster" : "planned");
        addProperty(request, "finalSync", finalSync != null ? finalSync : !disasterFailover);
        addProperty(request, "skipSourceFenceRequest", skipSourceFenceRequest);
        addProperty(request, "sourceIsolationAcknowledged", sourceIsolationAcknowledged);
        addProperty(request, "sourceIsolationReason", StringUtils.trimToNull(sourceIsolationReason));
        if (restorePoint == null) {
            return;
        }
        if (StringUtils.isBlank(restorePoint.getSourceSnapshotRef())) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Selected DR restore point does not have an engine restore point reference");
        }
        addProperty(request, "restorePointRef", restorePoint.getSourceSnapshotRef());
        addProperty(request, "restorePointType", restorePoint.getRestorePointType());
        addProperty(request, "restorePointTargetReadyRpoSeconds", restorePoint.getTargetReadyRpoSeconds());
    }

    private DrRestorePointVO resolveRestorePoint() {
        DrRestorePointVO latest = drRestorePointDao.findLatestTargetReadyByPlanId(getPlanId());
        Long restorePointId = getRestorePointId();
        if (restorePointId != null && (latest == null || latest.getId() != restorePointId.longValue())) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR,
                    "DR failover uses the latest synchronized target checkpoint; historical checkpoint selection is not supported");
        }
        return latest;
    }

    @Override
    protected String getApiName() {
        return APINAME;
    }

    @Override
    public String getEventType() {
        return DisasterRecoveryClusterEventTypes.EVENT_DR_PLAN_FAILOVER;
    }

    @Override
    public String getEventDescription() {
        return "Starting DR failover for plan " + getPlanId();
    }
}

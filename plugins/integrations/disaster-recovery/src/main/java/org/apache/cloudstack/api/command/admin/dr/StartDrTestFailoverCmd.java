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
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.dr.DrRunResponse;
import org.apache.commons.lang3.StringUtils;

import com.cloud.dr.DrRestorePointVO;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.cluster.DisasterRecoveryClusterEventTypes;
import com.google.gson.JsonObject;

@APICommand(name = StartDrTestFailoverCmd.APINAME, description = "Start a DR test failover run", responseObject = DrRunResponse.class,
        responseView = ResponseObject.ResponseView.Full, authorized = {RoleType.Admin})
public class StartDrTestFailoverCmd extends AbstractDrPlanActionCmd {
    public static final String APINAME = "startDrTestFailover";

    @Inject
    private DrRestorePointDao drRestorePointDao;

    @Parameter(name = "networkmode", type = CommandType.STRING,
            description = "test network mode: ISOLATED_NETWORK, PRODUCTION_NETWORK, or NIC_DISABLED (legacy NO_NIC is accepted as disabled adapters)")
    private String networkMode;

    @Parameter(name = "networkid", type = CommandType.UUID, entityType = NetworkResponse.class,
            description = "the Cloud-managed network used by the temporary test VM; required unless networkmode is NO_NIC")
    private Long networkId;

    @Parameter(name = "bootvalidationmode", type = CommandType.STRING, description = "test boot validation mode: POWER_STATE_ONLY or QGA_REQUIRED")
    private String bootValidationMode;

    @Parameter(name = "boottimeoutseconds", type = CommandType.INTEGER, description = "test boot validation timeout in seconds")
    private Integer bootTimeoutSeconds;

    @Parameter(name = "sourceindependent", type = CommandType.BOOLEAN,
            description = "use an existing sealed target checkpoint without contacting the source")
    private Boolean sourceIndependent;

    @Override
    protected String getRunType() {
        return "TEST_FAILOVER";
    }

    @Override
    protected String getApiName() {
        return APINAME;
    }

    @Override
    protected void addRequestProperties(JsonObject request) {
        String normalizedNetworkMode = StringUtils.defaultIfBlank(StringUtils.upperCase(networkMode), "ISOLATED_NETWORK");
        if (!StringUtils.equals(normalizedNetworkMode, "NO_NIC") && networkId == null) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR,
                    "networkid is required for Cloud-managed DR test failover unless networkmode is NO_NIC");
        }
        if (Boolean.TRUE.equals(sourceIndependent) && !StringUtils.equalsAny(normalizedNetworkMode, "ISOLATED_NETWORK", "ISOLATED", "NO_NIC", "NIC_DISABLED")) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Source-independent tests require an isolated network or NIC_DISABLED (legacy NO_NIC is accepted as disabled adapters)");
        }
        request.addProperty("sourceIndependent", Boolean.TRUE.equals(sourceIndependent));
        addProperty(request, "networkMode", normalizedNetworkMode);
        addProperty(request, "networkId", networkId);
        addProperty(request, "testBootValidationMode", StringUtils.upperCase(bootValidationMode));
        addProperty(request, "testBootTimeoutSeconds", bootTimeoutSeconds);
        DrRestorePointVO restorePoint = resolveRestorePoint();
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
                    "DR test failover uses the latest synchronized target checkpoint; historical checkpoint selection is not supported");
        }
        return latest;
    }

    @Override
    public String getEventType() {
        return DisasterRecoveryClusterEventTypes.EVENT_DR_PLAN_TEST_FAILOVER;
    }

    @Override
    public String getEventDescription() {
        return "Starting DR test failover for plan " + getPlanId();
    }
}

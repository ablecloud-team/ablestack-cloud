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
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.dr.cluster.DisasterRecoveryClusterEventTypes;
import com.cloud.dr.cluster.DisasterRecoveryClusterService;
import com.cloud.dr.cluster.dao.DisasterRecoveryClusterDao;
import com.cloud.utils.exception.CloudRuntimeException;

@APICommand(name = DeleteDisasterRecoveryClusterVmCmd.APINAME,
        description = "Delete Disaster Recovery Cluster virtual machine",
        responseObject = SuccessResponse.class,
        authorized = {RoleType.Admin})
public class DeleteDisasterRecoveryClusterVmCmd extends BaseAsyncCmd {
    public static final String APINAME = "deleteDisasterRecoveryClusterVm";

    @Inject
    public DisasterRecoveryClusterService disasterRecoveryClusterService;
    @Inject
    private DisasterRecoveryClusterDao disasterRecoveryClusterDao;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
                type = CommandType.UUID,
                entityType = UserVmResponse.class,
                required = true,
                description = "the virtual machine ID used by disaster recovery cluster")
    private Long id;

    @Parameter(name = ApiConstants.DR_CLUSTER_NAME,
                type = CommandType.STRING,
                required = true,
                description = "disaster recovery cluster name")
    private String drClusterName;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    public String getDrClusterName() {
        return drClusterName;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() throws ServerApiException {
        try {
            if (!disasterRecoveryClusterService.deleteDisasterRecoveryClusterVm(this)) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Failed to delete disaster recovery cluster virtual machine ID: %d", getId()));
            }
            SuccessResponse response = new SuccessResponse(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }

    @Override
    public String getEventType() {
        return DisasterRecoveryClusterEventTypes.EVENT_DR_VM_DELETE;
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.DisasterRecoveryCluster;
    }

    @Override
    public Long getApiResourceId() {
        return getId();
    }

    @Override
    public String getEventDescription() {
        String description = "Deleting disaster recovery cluster virtual machine";
        description += String.format(" ID: %d", getId());
        return description;
    }

}

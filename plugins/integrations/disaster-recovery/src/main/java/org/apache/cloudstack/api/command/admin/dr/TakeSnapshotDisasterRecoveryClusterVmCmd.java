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
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.dr.cluster.DisasterRecoveryClusterService;
import com.cloud.dr.cluster.dao.DisasterRecoveryClusterDao;
import com.cloud.utils.exception.CloudRuntimeException;

@APICommand(name = TakeSnapshotDisasterRecoveryClusterVmCmd.APINAME,
        description = "Take snapshot a disaster recovery cluster virtual machine",
        responseObject = SuccessResponse.class,
        authorized = {RoleType.Admin})
public class TakeSnapshotDisasterRecoveryClusterVmCmd extends BaseCmd implements AdminCmd {
    public static final String APINAME = "takeSnapshotDisasterRecoveryClusterVm";

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

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() throws ServerApiException {
        try {
            if (!disasterRecoveryClusterService.takeSnapshotDisasterRecoveryClusterVm(this)) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Failed to take snapshot disaster recovery cluster virtual machine ID: %d", getId()));
            }
            SuccessResponse response = new SuccessResponse(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
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
}
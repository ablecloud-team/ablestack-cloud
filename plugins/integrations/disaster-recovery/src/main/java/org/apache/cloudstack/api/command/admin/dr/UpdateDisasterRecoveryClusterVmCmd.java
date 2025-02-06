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

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.dr.cluster.GetDisasterRecoveryClusterVmListResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.dr.cluster.DisasterRecoveryClusterVmMap;
import com.cloud.dr.cluster.DisasterRecoveryClusterService;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.utils.exception.CloudRuntimeException;

import javax.inject.Inject;

@APICommand(name = UpdateDisasterRecoveryClusterVmCmd.APINAME,
        description = "Update a disaster recovery vm map",
        responseObject = GetDisasterRecoveryClusterVmListResponse.class,
        responseView = ResponseObject.ResponseView.Full,
        entityType = {DisasterRecoveryClusterVmMap.class},
        authorized = {RoleType.Admin})
public class UpdateDisasterRecoveryClusterVmCmd extends BaseCmd implements AdminCmd {
    public static final String APINAME = "updateDisasterRecoveryClusterVm";

    @Inject
    private DisasterRecoveryClusterService disasterRecoveryClusterService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID,
            entityType = GetDisasterRecoveryClusterVmListResponse.class,
            description = "the ID of the disaster recovery vm map")
    private Long id;

    @Parameter(name = ApiConstants.DR_CLUSTER_NAME, type = CommandType.STRING, description = "the name of the disaster recovery cluster vm")
    private String drClusterName;

    @Parameter(name = ApiConstants.DR_CLUSTER_VM_ID, type = CommandType.STRING, description = "the id of the disaster recovery cluster vm")
    private String drClusterVmId;

    @Parameter(name = ApiConstants.DR_CLUSTER_MIRROR_VM_ID, type = CommandType.STRING, description = "the id of the disaster recovery cluster mirror vm")
    private String mirrorVmId;

    @Parameter(name = ApiConstants.DR_CLUSTER_MIRROR_VM_NAME, type = CommandType.STRING, description = "the name of the disaster recovery cluster mirror vm")
    private String mirrorVmName;

    @Parameter(name = ApiConstants.DR_CLUSTER_MIRROR_VM_STATUS, type = CommandType.STRING, description = "the status of the disaster recovery cluster mirror vm")
    private String mirrorVmStatus;

    @Parameter(name = ApiConstants.DR_CLUSTER_MIRROR_VM_VOL_TYPE, type = CommandType.STRING, description = "the type of the disaster recovery cluster mirror vm volume")
    private String mirrorVmVolumeType;

    @Parameter(name = ApiConstants.DR_CLUSTER_MIRROR_VM_VOL_PATH, type = CommandType.STRING, description = "the path of the disaster recovery cluster mirror vm volume")
    private String mirrorVmVolumePath;

    @Parameter(name = ApiConstants.DR_CLUSTER_MIRROR_VM_VOL_STATUS, type = CommandType.STRING, description = "the status of the disaster recovery cluster mirror vm volume")
    private String mirrorVmVolumeStatus;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    public Long getId() {
        return id;
    }

    public String getDrClusterName() {
        return drClusterName;
    }

    public String getDrClusterVmId() {
        return drClusterVmId;
    }

    public String getMirrorVmId() {
        return mirrorVmId;
    }

    public String getMirrorVmName() {
        return mirrorVmName;
    }

    public String getMirrorVmStatus() {
        return mirrorVmStatus;
    }

    public String getMirrorVmVolumeType() {
        return mirrorVmVolumeType;
    }

    public String getMirrorVmVolumePath() {
        return mirrorVmVolumePath;
    }

    public String getMirrorVmVolumeStatus() {
        return mirrorVmVolumeStatus;
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////
    @Override
    public void execute() throws ServerApiException, ConcurrentOperationException {
        try {
            GetDisasterRecoveryClusterVmListResponse response = disasterRecoveryClusterService.updateDisasterRecoveryClusterVm(this);
            if (response == null) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update disaster recovery vm map");
            }
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }
}
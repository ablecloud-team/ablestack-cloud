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
import org.apache.cloudstack.api.BaseAsyncCreateCmd;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.dr.cluster.GetDisasterRecoveryClusterListResponse;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.context.CallContext;

import com.cloud.dr.cluster.DisasterRecoveryCluster;
import com.cloud.dr.cluster.DisasterRecoveryClusterEventTypes;
import com.cloud.dr.cluster.DisasterRecoveryClusterService;
import com.cloud.dr.cluster.DisasterRecoveryClusterVO;
import com.cloud.dr.cluster.dao.DisasterRecoveryClusterDao;
import com.cloud.utils.exception.CloudRuntimeException;

@APICommand(name = CreateDisasterRecoveryClusterCmd.APINAME,
        description = "Create Disaster Recovery Cluster",
        responseObject = GetDisasterRecoveryClusterListResponse.class,
        responseView = ResponseView.Restricted,
        entityType = {DisasterRecoveryCluster.class},
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true,
        authorized = {RoleType.Admin})
public class CreateDisasterRecoveryClusterCmd extends BaseAsyncCreateCmd {
    public static final String APINAME = "createDisasterRecoveryCluster";

    @Inject
    private DisasterRecoveryClusterService disasterRecoveryClusterService;
    @Inject
    private DisasterRecoveryClusterDao disasterRecoveryClusterDao;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true,
            description = "disaster recovery cluster name")
    private String name;

    @Parameter(name = ApiConstants.DESCRIPTION, type = CommandType.STRING, required = true,
            description = "disaster recovery cluster description")
    private String description;

    @Parameter(name = ApiConstants.DR_CLUSTER_URL, type = CommandType.STRING, required = true,
    description = "disaster recovery cluster mold url ex) http://10.10.1.10:8080")
    private String drClusterUrl;

    @Parameter(name = ApiConstants.DR_CLUSTER_TYPE, type = CommandType.STRING, required = true,
            description = "disaster recovery cluster type")
    private String drClusterType;

    @Parameter(name = ApiConstants.DR_CLUSTER_API_KEY, type = CommandType.STRING, required = true,
            description = "disaster recovery cluster mold api key")
    private String drClusterApiKey;

    @Parameter(name = ApiConstants.DR_CLUSTER_SECRET_KEY, type = CommandType.STRING, required = true,
            description = "disaster recovery cluster mold secret key")
    private String drClusterSecretKey;

    @Parameter(name = ApiConstants.DR_CLUSTER_PRIVATE_KEY, type = CommandType.STRING, required = false,
            description = "disaster recovery cluster glue private key",
            length = 16384)
    private String drClusterPrivateKey;

    @Parameter(name = ApiConstants.DR_CLUSTER_GLUE_IP_ADDRESS, type = CommandType.STRING, required = false,
            description = "disaster recovery cluster glue ip address")
    private String drClusterGlueIpAddress;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getDrClusterUrl() {
        return drClusterUrl;
    }

    public String getDrClusterType() {
        return drClusterType;
    }

    public String getDrClusterApiKey() {
        return drClusterApiKey;
    }

    public String getDrClusterSecretKey() {
        return drClusterSecretKey;
    }

    public String getDrClusterPrivateKey() {
        return drClusterPrivateKey;
    }

    public String getDrClusterGlueIpAddress() {
        return drClusterGlueIpAddress;
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }

    @Override
    public String getEventType() {
        return DisasterRecoveryClusterEventTypes.EVENT_DR_CREATE;
    }

    @Override
    public String getEventDescription() {
        return "Creating a disaster recovery cluster";
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.DisasterRecoveryCluster;
    }

    @Override
    public Long getApiResourceId() {
        return getEntityId();
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void create() throws CloudRuntimeException {
        try {
            DisasterRecoveryCluster result = disasterRecoveryClusterService.createDisasterRecoveryCluster(this);
            if (result != null) {
                setEntityId(result.getId());
                setEntityUuid(result.getUuid());
            } else {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to create disaster recovery cluster entity : " + name);
            }
        } catch (CloudRuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public void execute() throws CloudRuntimeException {
        try {
            DisasterRecoveryClusterVO drcluster = disasterRecoveryClusterDao.findById(getEntityId());
            if (drcluster.getDrClusterType().equalsIgnoreCase("secondary")) {
                if (!disasterRecoveryClusterService.setupDisasterRecoveryCluster(getEntityId())) {
                    throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to setup disaster recovery cluster");
                }
            }
            GetDisasterRecoveryClusterListResponse response = disasterRecoveryClusterService.createDisasterRecoveryClusterResponse(getEntityId());
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }
}

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

import com.cloud.dr.cluster.DisasterRecoveryCluster;
import com.cloud.dr.cluster.DisasterRecoveryClusterService;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.dr.cluster.GetDisasterRecoveryClusterListResponse;
import org.apache.cloudstack.context.CallContext;

import javax.inject.Inject;
import java.util.Collection;
import java.util.Map;

@APICommand(name = UpdateDisasterRecoveryClusterCmd.APINAME,
        description = "Update a disaster recovery",
        responseObject = GetDisasterRecoveryClusterListResponse.class,
        responseView = ResponseObject.ResponseView.Full,
        entityType = {DisasterRecoveryCluster.class},
        authorized = {RoleType.Admin})
public class UpdateDisasterRecoveryClusterCmd extends BaseCmd implements AdminCmd {
    public static final String APINAME = "updateDisasterRecoveryCluster";

    @Inject
    private DisasterRecoveryClusterService disasterRecoveryClusterService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID,
            entityType = GetDisasterRecoveryClusterListResponse.class,
            description = "the ID of the disaster recovery")
    private Long id;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, description = "name of disaster recovery cluster")
    private String name;

    @Parameter(name = ApiConstants.DESCRIPTION, type = CommandType.STRING, description = "the description of the disaster recovery cluster")
    private String description;

    @Parameter(name = ApiConstants.DR_CLUSTER_URL, type = CommandType.STRING, description = "the url of the disaster recovery cluster")
    private String drClusterUrl;

    @Parameter(name = ApiConstants.DR_CLUSTER_GLUE_IP_ADDRESS, type = CommandType.STRING, required = false,
            description = "dr cluster glue ip address")
    private String drClusterGlueIpAddress;

    @Parameter(name = ApiConstants.DR_CLUSTER_STATUS, type = CommandType.STRING,
            description = "the enabled or disabled dr cluster state of the disaster recovery")
    private String drClusterStatus;

    @Parameter(name = ApiConstants.MIRRORING_AGENT_STATUS, type = CommandType.STRING,
            description = "the enabled or disabled mirroring agent state of the disaster recovery")
    private String mirroringAgentStatus;

    @Parameter(name = ApiConstants.DETAILS, type = CommandType.MAP, description = "Details in key/value pairs. 'extraconfig' is not allowed to be passed in details.")
    protected Map<String, String> details;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getDrClusterUrl() {
        return drClusterUrl;
    }

    public String getDrClusterGlueIpAddress() {
        return drClusterGlueIpAddress;
    }

    public String getDrClusterStatus() {
        return drClusterStatus;
    }

    public String getMirroringAgentStatus() {
        return mirroringAgentStatus;
    }

    public Map<String, String> getDetails() {
        if (this.details == null || this.details.isEmpty()) {
            return null;
        }

        Collection<String> paramsCollection = this.details.values();
        return (Map<String, String>) (paramsCollection.toArray())[0];
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
            GetDisasterRecoveryClusterListResponse response = disasterRecoveryClusterService.updateDisasterRecoveryCluster(this);
            if (response == null) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update disaster recovery");
            }
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }
}
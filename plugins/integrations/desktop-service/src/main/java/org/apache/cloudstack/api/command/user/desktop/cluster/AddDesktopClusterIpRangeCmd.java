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

package org.apache.cloudstack.api.command.user.desktop.cluster;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCreateCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.DesktopClusterIpRangeResponse;
import org.apache.cloudstack.api.response.DesktopClusterResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.desktop.cluster.DesktopClusterIpRange;
import com.cloud.desktop.cluster.DesktopClusterEventTypes;
import com.cloud.desktop.cluster.DesktopClusterService;
import com.cloud.utils.exception.CloudRuntimeException;

@APICommand(name = AddDesktopClusterIpRangeCmd.APINAME,
        description = "Add a Desktop Cluster Ip Range",
        responseObject = DesktopClusterIpRangeResponse.class,
        responseView = ResponseView.Restricted,
        entityType = {DesktopClusterIpRange.class},
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class AddDesktopClusterIpRangeCmd extends BaseAsyncCreateCmd {
    public static final String APINAME = "addDesktopClusterIpRanges";

    @Inject
    private DesktopClusterService desktopService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.DESKTOP_CLUSTER_ID, type = CommandType.UUID,
            entityType = DesktopClusterResponse.class,
            description = "the ID of the Desktop Cluster")
    private Long desktopClusterId;

    @Parameter(name = ApiConstants.GATEWAY, type = CommandType.STRING, required = true,
            description = "the gateway of the Desktop Cluster IP Range")
    private String gateway;

    @Parameter(name = ApiConstants.NETMASK, type = CommandType.STRING, required = true,
            description = "the netmask of the Desktop Cluster IP Range")
    private String netmask;

    @Parameter(name = ApiConstants.START_IP, type = CommandType.STRING, required = true,
            description = "the start ip of the Desktop Cluster IP Range")
    private String startIp;

    @Parameter(name = ApiConstants.END_IP, type = CommandType.STRING, required = true,
            description = "the end ip of the Desktop Cluster IP Range")
    private String endIp;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getDesktopClusterId() {
        return desktopClusterId;
    }

    public String getGateway() {
        return gateway;
    }

    public String getNetmask() {
        return netmask;
    }

    public String getStartIp() {
        return startIp;
    }

    public String getEndIp() {
        return endIp;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    public static String getResultObjectName() {
        return "desktopclusteriprange";
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }

    @Override
    public String getEventType() {
        return DesktopClusterEventTypes.EVENT_DESKTOP_CLUSTER_IP_RANGE_ADD;
    }

    @Override
    public String getCreateEventType() {
        return DesktopClusterEventTypes.EVENT_DESKTOP_CLUSTER_IP_RANGE_ADD;
    }

    @Override
    public String getCreateEventDescription() {
        return "adding Desktop cluster ip range";
    }

    @Override
    public String getEventDescription() {
        return "Adding Desktop cluster ip range. Ip range Id: " + getEntityId();
    }

    @Override
    public void execute() {
        try {
            DesktopClusterIpRangeResponse response = desktopService.addDesktopClusterIpRangeResponse(getEntityId());
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public void create() throws CloudRuntimeException {
        try {
            DesktopClusterIpRange cluster = desktopService.addDesktopClusterIpRange(this);
            if (cluster == null) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to add desktop cluster ip range");
            }
            setEntityId(cluster.getId());
            setEntityUuid(cluster.getUuid());
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }
}

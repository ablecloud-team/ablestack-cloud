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
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCreateCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.DesktopClusterResponse;
import org.apache.cloudstack.api.response.DesktopControllerVersionResponse;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.desktop.cluster.DesktopCluster;
import com.cloud.desktop.cluster.DesktopClusterEventTypes;
import com.cloud.desktop.cluster.DesktopClusterService;
import com.cloud.utils.exception.CloudRuntimeException;

@APICommand(name = CreateDesktopClusterCmd.APINAME,
        description = "Creates a Desktop cluster",
        responseObject = DesktopClusterResponse.class,
        responseView = ResponseView.Restricted,
        entityType = {DesktopCluster.class},
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class CreateDesktopClusterCmd extends BaseAsyncCreateCmd {
    public static final String APINAME = "createDesktopCluster";

    @Inject
    public DesktopClusterService DesktopClusterService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true, description = "name for the Desktop cluster")
    private String name;

    @Parameter(name = ApiConstants.DESCRIPTION, type = CommandType.STRING, required = true, description = "description for the Desktop cluster")
    private String description;

    // @Parameter(name = ApiConstants.DESKTOP_PASSWORD, type = CommandType.STRING, required = true, description = "password for the Desktop cluster")
    // private String password;

    @Parameter(name = ApiConstants.DESKTOP_CONTROLLER_VERSION, type = CommandType.UUID, entityType = DesktopControllerVersionResponse.class, required = true,
            description = "Desktop version with which cluster to be launched")
    private Long controllerVersion;

    @ACL(accessType = AccessType.UseEntry)
    @Parameter(name = ApiConstants.SERVICE_OFFERING_ID, type = CommandType.UUID, entityType = ServiceOfferingResponse.class,
            required = true, description = "the ID of the service offering for the virtual machines in the cluster.")
    private Long serviceOfferingId;

    @ACL(accessType = AccessType.UseEntry)
    @Parameter(name = ApiConstants.ACCOUNT, type = CommandType.STRING, description = "an optional account for the" +
            " virtual machine. Must be used with domainId.")
    private String accountName;

    @ACL(accessType = AccessType.UseEntry)
    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class,
            description = "an optional domainId for the virtual machine. If the account parameter is used, domainId must also be used.")
    private Long domainId;

    @ACL(accessType = AccessType.UseEntry)
    @Parameter(name = ApiConstants.PROJECT_ID, type = CommandType.UUID, entityType = ProjectResponse.class,
            description = "Deploy cluster for the project")
    private Long projectId;

    @ACL(accessType = AccessType.UseEntry)
    @Parameter(name = ApiConstants.NETWORK_ID, type = CommandType.UUID, entityType = NetworkResponse.class, required = true,
            description = "Network in which Desktop cluster is to be launched")
    private Long networkId;

    @Parameter(name = ApiConstants.DESKTOP_AD_DOMAIN_NAME, type = CommandType.STRING, required = true,
            description = "windows active directory domain name for desktop cluster")
    private String adDomainName;

    @Parameter(name = ApiConstants.DESKTOP_CLUSTER_TYPE, type = CommandType.STRING, required = true,
            description = "access type for Desktop cluster")
    private String accessType;

    @Parameter(name = ApiConstants.GATEWAY, type = CommandType.STRING,
    description = "Gateway for L2 Network of desktop cluster")
    private String gateway;

    @Parameter(name = ApiConstants.NETMASK, type = CommandType.STRING,
    description = "Netmask for L2 Network of desktop cluster")
    private String netmask;

    @Parameter(name = ApiConstants.START_IP, type = CommandType.STRING,
    description = "Start IP for L2 Network of desktop cluster")
    private String startIp;

    @Parameter(name = ApiConstants.END_IP, type = CommandType.STRING,
    description = "End IP for L2 Network of desktop cluster")
    private String endIp;

    @Parameter(name = ApiConstants.DESKTOP_CONTROLLER_DC_IP, type = CommandType.STRING, required = true,
            description = "DC IP for the desktop controller")
    private String dcIp;

    @Parameter(name = ApiConstants.DESKTOP_CONTROLLER_WORKS_IP, type = CommandType.STRING, required = true,
            description = "WORKS IP for the desktop controller")
    private String worksIp;


    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public String getAccountName() {
        if (accountName == null) {
            return CallContext.current().getCallingAccount().getAccountName();
        }
        return accountName;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    // public String getPassword() {
    //     return password;
    // }

    public Long getDomainId() {
        if (domainId == null) {
            return CallContext.current().getCallingAccount().getDomainId();
        }
        return domainId;
    }

    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public Long getControllerVersion() {
        return controllerVersion;
    }

    public Long getNetworkId() {
        return networkId;
    }

    public String getAdDomainName() {
        return adDomainName;
    }

    public String getAccessType() {
        return accessType;
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

    public String getDcIp() {
        return dcIp;
    }

    public String getWorksIp() {
        return worksIp;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    public static String getResultObjectName() {
        return "desktopcluster";
    }

    @Override
    public long getEntityOwnerId() {
        Long accountId = _accountService.finalyzeAccountId(accountName, domainId, projectId, true);
        if (accountId == null) {
            return CallContext.current().getCallingAccount().getId();
        }

        return accountId;
    }

    @Override
    public String getEventType() {
        return DesktopClusterEventTypes.EVENT_DESKTOP_CLUSTER_CREATE;
    }

    @Override
    public String getCreateEventType() {
        return DesktopClusterEventTypes.EVENT_DESKTOP_CLUSTER_CREATE;
    }

    @Override
    public String getCreateEventDescription() {
        return "creating Desktop cluster";
    }

    @Override
    public String getEventDescription() {
        return "Creating Desktop cluster. Cluster Id: " + getEntityId();
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.VirtualMachine;
    }

    @Override
    public void execute() {
        try {
            if (!DesktopClusterService.startDesktopCluster(getEntityId(), true)) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to start Desktop cluster");
            }
            DesktopClusterResponse response = DesktopClusterService.createDesktopClusterResponse(getEntityId());
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public void create() throws CloudRuntimeException {
        try {
            DesktopCluster cluster = DesktopClusterService.createDesktopCluster(this);
            if (cluster == null) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to create Desktop cluster");
            }
            setEntityId(cluster.getId());
            setEntityUuid(cluster.getUuid());
        } catch (CloudRuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }
}

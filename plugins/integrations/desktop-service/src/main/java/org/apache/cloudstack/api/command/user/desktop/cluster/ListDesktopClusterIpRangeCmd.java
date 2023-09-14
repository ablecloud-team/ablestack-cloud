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
import org.apache.cloudstack.api.BaseListProjectAndAccountResourcesCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.DesktopClusterIpRangeResponse;
import org.apache.cloudstack.api.response.DesktopClusterResponse;
import org.apache.cloudstack.api.response.ListResponse;

import com.cloud.desktop.cluster.DesktopClusterService;
import com.cloud.utils.exception.CloudRuntimeException;

@APICommand(name = ListDesktopClusterIpRangeCmd.APINAME,
        description = "Lists Desktop Cluster IP Range",
        responseObject = DesktopClusterIpRangeResponse.class,
        responseView = ResponseView.Restricted,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ListDesktopClusterIpRangeCmd extends BaseListProjectAndAccountResourcesCmd {
    public static final String APINAME = "listDesktopClusterIpRanges";

    @Inject
    public DesktopClusterService desktopService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID,
            entityType = DesktopClusterIpRangeResponse.class,
            description = "the ID of the Desktop Cluster IP Range")
    private Long id;

    @Parameter(name = ApiConstants.DESKTOP_CLUSTER_ID, type = CommandType.UUID,
            entityType = DesktopClusterResponse.class,
            description = "the ID of the Desktop Cluster")
    private Long desktopClusterId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    public Long getDesktopClusterId() {
        return desktopClusterId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public void execute() throws ServerApiException {
        try {
            ListResponse<DesktopClusterIpRangeResponse> response = desktopService.listDesktopClusterIpRanges(this);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }
}

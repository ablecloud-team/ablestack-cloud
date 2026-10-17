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
package org.apache.cloudstack.api.command.user.kubernetes.cluster;

import java.util.Map;
import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.CniConfigurationPreviewResponse;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.UserDataResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.userdata.UserDataManager;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.kubernetes.cluster.CniConfigurationRenderer;
import com.cloud.network.dao.NetworkVO;
import com.cloud.dc.dao.ASNumberDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.user.AccountManager;
import com.cloud.user.UserDataVO;
import com.cloud.user.dao.UserDataDao;

@APICommand(name = "previewCniConfiguration", description = "Inspect CNI variable candidates and preview the server CNI payload without provisioning resources",
        responseObject = CniConfigurationPreviewResponse.class, requestHasSensitiveInfo = true, responseHasSensitiveInfo = true,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class PreviewCniConfigurationCmd extends BaseCmd {
    @Inject private UserDataDao userDataDao;
    @Inject private UserDataManager userDataManager;
    @Inject private AccountManager accountManager;
    @Inject private NetworkDao networkDao;
    @Inject private ASNumberDao asNumberDao;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = UserDataResponse.class, description = "Registered CNI UUID; mutually exclusive with cniconfig") private Long id;
    @Parameter(name = ApiConstants.CNI_CONFIG, type = CommandType.STRING, length = 1048576, description = "Base64 local draft; mutually exclusive with id") private String cniConfig;
    @Parameter(name = "params", type = CommandType.STRING, length = 4096, description = "Comma-separated declared variable names, for local drafts only") private String params;
    @Parameter(name = ApiConstants.CNI_CONFIG_DETAILS, type = CommandType.MAP, description = "Custom values, for example cniconfigdetails[0].KEY=value") private Map details;
    @Parameter(name = ApiConstants.AS_NUMBER, type = CommandType.LONG, description = "Proposed AS number when the network has no assigned value") private Long asNumber;
    @Parameter(name = ApiConstants.NETWORK_ID, type = CommandType.UUID, entityType = NetworkResponse.class, description = "Selected network for resolving its assigned automatic AS number") private Long networkId;
    @Parameter(name = ApiConstants.PROJECT_ID, type = CommandType.UUID, entityType = ProjectResponse.class, description = "Project context") private Long projectId;

    @Override public long getEntityOwnerId() { return CallContext.current().getCallingAccountId(); }

    @Override public void execute() {
        if ((id == null) == (cniConfig == null) || id != null && params != null) {
            throw new InvalidParameterValueException("Provide either a registered CNI id or a local draft with params");
        }
        String encoded = cniConfig;
        String declared = params;
        UserDataVO cni = null;
        if (id != null) {
            cni = userDataDao.findById(id);
            if (cni == null) { throw new InvalidParameterValueException("Invalid CNI configuration"); }
            accountManager.checkAccess(CallContext.current().getCallingAccount(), AccessType.UseEntry, false, cni);
            if (!cni.isForCks()) { throw new InvalidParameterValueException("The requested resource is not a CNI configuration"); }
            encoded = cni.getUserData(); declared = cni.getParams();
        } else { encoded = userDataManager.validateUserData(encoded, getHttpMethod()); }
        Long resolvedAs = asNumber;
        if (networkId != null) {
            NetworkVO network = networkDao.findById(networkId);
            if (network == null) { throw new InvalidParameterValueException("Invalid network"); }
            accountManager.checkAccess(CallContext.current().getCallingAccount(), AccessType.UseEntry, false, network);
            resolvedAs = CniConfigurationRenderer.resolveAsNumber(network, asNumberDao, asNumber);
        }
        CniConfigurationPreviewResponse response = CniConfigurationRenderer.preview(encoded, declared, convertDetailsToMap(details), resolvedAs);
        userDataManager.validateUserData(response.userdata, HTTPMethod.POST);
        if (cni != null) { response.id = cni.getUuid(); }
        response.setObjectName("cnipreview");
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}

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

package org.apache.cloudstack.api.command.user.storage.dataservice;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.StorageAccessRuleResponse;
import org.apache.cloudstack.api.response.StorageSmbShareResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "createStorageSmbAcl",
        responseObject = StorageAccessRuleResponse.class,
        description = "Creates an SMB ACL rule for a share.",
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class CreateStorageSmbAclCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = "shareid", type = CommandType.UUID, entityType = StorageSmbShareResponse.class, required = true, description = "SMB share ID")
    private Long shareId;

    @Parameter(name = "principaltype", type = CommandType.STRING, required = true, description = "LOCAL_USER, LOCAL_GROUP, AD_USER, or AD_GROUP")
    private String principalType;

    @Parameter(name = "principal", type = CommandType.STRING, required = true, description = "user or group name")
    private String principal;

    @Parameter(name = "permission", type = CommandType.STRING, required = true, description = "READ_ONLY, READ_WRITE, or ADMIN")
    private String permission;

    @Parameter(name = "password", type = CommandType.STRING, description = "optional local SMB user password. Used only for LOCAL_USER creation and not stored.")
    private String password;

    public Long getShareId() {
        return shareId;
    }

    public String getPrincipalType() {
        return principalType;
    }

    public String getPrincipal() {
        return principal;
    }

    public String getPermission() {
        return permission;
    }

    public String getPassword() {
        return password;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.SMB.ACL.CREATE";
    }

    @Override
    public String getEventDescription() {
        return "Creating Storage Service SMB ACL for share " + shareId;
    }

    @Override
    public void execute() {
        StorageAccessRuleResponse response = storageService.createStorageSmbAcl(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to create SMB ACL");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}

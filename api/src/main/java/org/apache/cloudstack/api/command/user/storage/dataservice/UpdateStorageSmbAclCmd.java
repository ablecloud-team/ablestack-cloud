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
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.StorageAccessRuleResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "updateStorageSmbAcl",
        responseObject = StorageAccessRuleResponse.class,
        description = "Updates an SMB ACL rule.",
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class UpdateStorageSmbAclCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = StorageAccessRuleResponse.class, required = true, description = "ACL ID")
    private Long id;

    @Parameter(name = "principal", type = CommandType.STRING, description = "user or group name")
    private String principal;

    @Parameter(name = "permission", type = CommandType.STRING, description = "READ_ONLY, READ_WRITE, or ADMIN")
    private String permission;

    @Parameter(name = "password", type = CommandType.STRING, description = "optional local SMB user password. Used only for LOCAL_USER and not stored.")
    private String password;

    public Long getId() {
        return id;
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
        return "STORAGE.SMB.ACL.UPDATE";
    }

    @Override
    public String getEventDescription() {
        return "Updating Storage Service SMB ACL " + id;
    }

    @Override
    public void execute() {
        StorageAccessRuleResponse response = storageService.updateStorageSmbAcl(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update SMB ACL");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}

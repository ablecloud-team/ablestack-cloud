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
import org.apache.cloudstack.api.response.StorageIdentityDomainResponse;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "leaveStorageServiceFromAdDomain",
        responseObject = StorageIdentityDomainResponse.class,
        description = "Leaves the Active Directory domain used by a Storage Service instance.",
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class LeaveStorageServiceFromAdDomainCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, required = true, description = "Storage Service instance ID")
    private Long instanceId;

    @Parameter(name = "username", type = CommandType.STRING, description = "domain leave user")
    private String username;

    @Parameter(name = "password", type = CommandType.STRING, description = "domain leave password. Not stored.")
    private String password;

    public Long getInstanceId() {
        return instanceId;
    }

    public String getUsername() {
        return username;
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
        return "STORAGE.SMB.AD.LEAVE";
    }

    @Override
    public String getEventDescription() {
        return "Leaving AD domain for Storage Service instance " + instanceId;
    }

    @Override
    public void execute() {
        StorageIdentityDomainResponse response = storageService.leaveStorageServiceFromAdDomain(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to leave AD domain");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}

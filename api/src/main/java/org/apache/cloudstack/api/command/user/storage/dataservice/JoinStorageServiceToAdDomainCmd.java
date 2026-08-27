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

@APICommand(name = "joinStorageServiceToAdDomain",
        responseObject = StorageIdentityDomainResponse.class,
        description = "Joins a Storage Service instance to an Active Directory domain for SMB.",
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class JoinStorageServiceToAdDomainCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, required = true, description = "Storage Service instance ID")
    private Long instanceId;

    @Parameter(name = "domainname", type = CommandType.STRING, required = true, description = "Active Directory domain name")
    private String domainName;

    @Parameter(name = "username", type = CommandType.STRING, required = true, description = "domain join user")
    private String username;

    @Parameter(name = "password", type = CommandType.STRING, required = true, description = "domain join password. Not stored.")
    private String password;

    @Parameter(name = "organizationalunit", type = CommandType.STRING, description = "organizational unit for the computer account")
    private String organizationalUnit;

    @Parameter(name = "dnsservers", type = CommandType.STRING, description = "comma-separated DNS servers")
    private String dnsServers;

    @Parameter(name = "workgroup", type = CommandType.STRING, description = "NetBIOS workgroup")
    private String workgroup;

    public Long getInstanceId() {
        return instanceId;
    }

    public String getDomainName() {
        return domainName;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getOrganizationalUnit() {
        return organizationalUnit;
    }

    public String getDnsServers() {
        return dnsServers;
    }

    public String getWorkgroup() {
        return workgroup;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.SMB.AD.JOIN";
    }

    @Override
    public String getEventDescription() {
        return "Joining Storage Service instance " + instanceId + " to AD domain " + domainName;
    }

    @Override
    public void execute() {
        StorageIdentityDomainResponse response = storageService.joinStorageServiceToAdDomain(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to join AD domain");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}

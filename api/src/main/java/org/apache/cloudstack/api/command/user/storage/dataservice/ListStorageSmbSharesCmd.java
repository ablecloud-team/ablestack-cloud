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
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageSmbShareResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "listStorageSmbShares",
        responseObject = StorageSmbShareResponse.class,
        description = "Lists SMB shares.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ListStorageSmbSharesCmd extends BaseListCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = StorageSmbShareResponse.class, description = "SMB share ID")
    private Long id;

    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, description = "Storage Service instance ID")
    private Long instanceId;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, description = "SMB share name")
    private String name;

    public Long getId() {
        return id;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public String getName() {
        return name;
    }

    @Override
    public void execute() {
        ListResponse<StorageSmbShareResponse> response = storageService.listStorageSmbShares(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}

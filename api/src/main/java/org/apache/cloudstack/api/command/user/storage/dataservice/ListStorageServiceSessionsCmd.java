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
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.SharedFSResponse;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "listStorageServiceSessions",
        responseObject = StorageServiceRuntimeResponse.class,
        description = "Lists active protocol sessions for Storage Service instances.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ListStorageServiceSessionsCmd extends BaseListCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, description = "Storage Service instance ID")
    private Long instanceId;

    @Parameter(name = "sharedfilesystemid", type = CommandType.UUID, entityType = SharedFSResponse.class, description = "Shared FileSystem ID used to resolve the active Storage Service instance")
    private Long sharedFileSystemId;

    @Parameter(name = "protocol", type = CommandType.STRING, description = "Protocol filter: NFS, SMB, ISCSI, or NVME_OF")
    private String protocol;

    @Parameter(name = "resourceid", type = CommandType.STRING, description = "Optional protocol resource identifier filter")
    private String resourceId;

    @Parameter(name = "client", type = CommandType.STRING, description = "Optional client address filter")
    private String client;

    @Parameter(name = "state", type = CommandType.STRING, description = "Optional session state filter")
    private String state;

    public Long getInstanceId() {
        return instanceId;
    }

    public Long getSharedFileSystemId() {
        return sharedFileSystemId;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getClient() {
        return client;
    }

    public String getState() {
        return state;
    }

    @Override
    public void execute() {
        ListResponse<StorageServiceRuntimeResponse> response = storageService.listStorageServiceSessions(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}

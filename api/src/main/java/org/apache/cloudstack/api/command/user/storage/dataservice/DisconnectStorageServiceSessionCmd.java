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
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "disconnectStorageServiceSession",
        responseObject = StorageServiceRuntimeResponse.class,
        description = "Disconnects an active Storage Service protocol session.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class DisconnectStorageServiceSessionCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, required = true,
            description = "Storage Service instance ID")
    private Long instanceId;

    @Parameter(name = "protocol", type = CommandType.STRING, required = true, description = "Protocol: NFS, SMB, ISCSI, or NVME_OF")
    private String protocol;

    @Parameter(name = "sessionid", type = CommandType.STRING, description = "Runtime session ID when provided by the Storage Service VM")
    private String sessionId;

    @Parameter(name = "peer", type = CommandType.STRING, description = "Client endpoint to disconnect")
    private String peer;

    @Parameter(name = "local", type = CommandType.STRING, description = "Local service endpoint")
    private String local;

    @Parameter(name = "resourceid", type = CommandType.STRING, description = "Optional protocol resource identifier")
    private String resourceId;

    @Parameter(name = "force", type = CommandType.BOOLEAN, description = "Force the disconnect when supported")
    private Boolean force;

    public Long getInstanceId() {
        return instanceId;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getPeer() {
        return peer;
    }

    public String getLocal() {
        return local;
    }

    public String getResourceId() {
        return resourceId;
    }

    public Boolean getForce() {
        return force;
    }

    @Override
    public void execute() {
        StorageServiceRuntimeResponse response = storageService.disconnectStorageServiceSession(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }

    @Override
    public String getEventType() {
        return "STORAGE.SERVICE.SESSION.DISCONNECT";
    }

    @Override
    public String getEventDescription() {
        return "Disconnecting Storage Service session for account " + CallContext.current().getCallingAccount().getId();
    }
}

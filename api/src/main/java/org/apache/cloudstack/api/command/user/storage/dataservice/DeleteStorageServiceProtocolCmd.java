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
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "deleteStorageServiceProtocol",
        responseObject = SuccessResponse.class,
        description = "Disables a protocol on a Storage Service instance.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class DeleteStorageServiceProtocolCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, required = true,
            description = "Storage Service instance ID")
    private Long instanceId;

    @Parameter(name = "protocol", type = CommandType.STRING, required = true, description = "protocol to disable")
    private String protocol;

    @Parameter(name = "listenip", type = CommandType.STRING,
            description = "optional listen IP endpoint to remove from the protocol instead of disabling the whole protocol")
    private String listenIp;

    @Parameter(name = "port", type = CommandType.INTEGER,
            description = "optional listener port endpoint to remove together with listenip")
    private Integer port;

    public Long getInstanceId() {
        return instanceId;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getListenIp() {
        return listenIp;
    }

    public Integer getPort() {
        return port;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.SERVICE.PROTOCOL.DELETE";
    }

    @Override
    public String getEventDescription() {
        if (listenIp != null) {
            return "Removing Storage Service protocol endpoint " + listenIp + (port == null ? "" : ":" + port) + " for " + protocol + " on instance " + instanceId;
        }
        return "Disabling Storage Service protocol " + protocol + " on instance " + instanceId;
    }

    @Override
    public void execute() {
        boolean result = storageService.deleteStorageServiceProtocol(this);
        SuccessResponse response = new SuccessResponse(getCommandName());
        response.setSuccess(result);
        setResponseObject(response);
    }
}

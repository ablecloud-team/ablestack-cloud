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
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageServiceProtocolResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "enableStorageServiceProtocol",
        responseObject = StorageServiceProtocolResponse.class,
        description = "Enables a protocol on a Storage Service instance. Phase 2 supports NFS.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class EnableStorageServiceProtocolCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, required = true, description = "Storage Service instance ID")
    private Long instanceId;

    @Parameter(name = "protocol", type = CommandType.STRING, required = true, description = "protocol to enable. Phase 2 supports NFS.")
    private String protocol;

    @Parameter(name = "listenip", type = CommandType.STRING, description = "protocol listen IP")
    private String listenIp;

    @Parameter(name = "port", type = CommandType.INTEGER, description = "protocol port")
    private Integer port;

    @Parameter(name = "protocolmode", type = CommandType.STRING, description = "NFS protocol mode. Supported values are V4_ONLY and V3V4_DUAL")
    private String protocolMode;

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

    public String getProtocolMode() {
        return protocolMode;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.SERVICE.PROTOCOL.ENABLE";
    }

    @Override
    public String getEventDescription() {
        return "Enabling Storage Service protocol " + protocol + " on instance " + instanceId;
    }

    @Override
    public void execute() {
        StorageServiceProtocolResponse response = storageService.enableStorageServiceProtocol(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to enable Storage Service protocol");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}

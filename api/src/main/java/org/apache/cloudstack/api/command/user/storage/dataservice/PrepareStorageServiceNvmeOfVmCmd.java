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
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "prepareStorageServiceNvmeOfVm",
        responseObject = StorageServiceRuntimeResponse.class,
        description = "Validates or prepares NVMe-oF runtime prerequisites inside a Storage Service System VM.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class PrepareStorageServiceNvmeOfVmCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, required = true, description = "Storage Service instance ID")
    private Long instanceId;

    @Parameter(name = "engine", type = CommandType.STRING, description = "NVMe-oF engine: KERNEL_NVMET or SPDK")
    private String engine;

    @Parameter(name = "transport", type = CommandType.STRING, description = "NVMe-oF transport, initially tcp")
    private String transport;

    @Parameter(name = "runtimecapabilityprofileid", type = CommandType.STRING, description = "future VM Runtime Capability profile ID for SPDK mode")
    private String runtimeCapabilityProfileId;

    @Parameter(name = "validateonly", type = CommandType.BOOLEAN, description = "validate prerequisites without changing the System VM")
    private Boolean validateOnly;

    public Long getInstanceId() {
        return instanceId;
    }

    public String getEngine() {
        return engine;
    }

    public String getTransport() {
        return transport;
    }

    public String getRuntimeCapabilityProfileId() {
        return runtimeCapabilityProfileId;
    }

    public Boolean getValidateOnly() {
        return validateOnly;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.NVMEOF.PREPARE";
    }

    @Override
    public String getEventDescription() {
        return "Preparing NVMe-oF runtime for Storage Service instance " + instanceId;
    }

    @Override
    public void execute() {
        StorageServiceRuntimeResponse response = storageService.prepareStorageServiceNvmeOfVm(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to prepare NVMe-oF runtime");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}

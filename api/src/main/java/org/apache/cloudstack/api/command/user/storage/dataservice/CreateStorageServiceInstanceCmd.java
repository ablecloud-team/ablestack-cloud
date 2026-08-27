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
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.SystemVmResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.StorageService;
import org.apache.cloudstack.storage.dataservice.StorageServiceInstance;

@APICommand(name = "createStorageServiceInstance",
        responseObject = StorageServiceInstanceResponse.class,
        description = "Creates a Storage Service instance record and optionally binds it to an existing System VM.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class CreateStorageServiceInstanceCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true, description = "Storage Service instance name")
    private String name;

    @Parameter(name = ApiConstants.DESCRIPTION, type = CommandType.STRING, description = "Storage Service instance description")
    private String description;

    @Parameter(name = ApiConstants.ACCOUNT, type = CommandType.STRING, description = "account associated with the Storage Service instance")
    private String accountName;

    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class, description = "domain ID associated with the Storage Service instance")
    private Long domainId;

    @Parameter(name = ApiConstants.PROJECT_ID, type = CommandType.UUID, entityType = ProjectResponse.class, description = "project associated with the Storage Service instance")
    private Long projectId;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class, required = true, description = "zone ID")
    private Long zoneId;

    @Parameter(name = ApiConstants.SERVICE_OFFERING_ID, type = CommandType.UUID, entityType = ServiceOfferingResponse.class, description = "service offering ID")
    private Long serviceOfferingId;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID, type = CommandType.UUID, entityType = SystemVmResponse.class, description = "existing Storage Service System VM ID")
    private Long virtualMachineId;

    @Parameter(name = ApiConstants.PROVIDER, type = CommandType.STRING, description = "Storage Service provider")
    private String provider;

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    public String getProvider() {
        return provider == null ? StorageServiceInstance.StorageServiceProviderName : provider;
    }

    @Override
    public long getEntityOwnerId() {
        Long accountId = _accountService.finalyzeAccountId(accountName, domainId, projectId, true);
        return accountId == null ? CallContext.current().getCallingAccount().getId() : accountId;
    }

    @Override
    public String getEventType() {
        return "STORAGE.SERVICE.INSTANCE.CREATE";
    }

    @Override
    public String getEventDescription() {
        return "Creating Storage Service instance " + name;
    }

    @Override
    public void execute() {
        StorageServiceInstanceResponse response = storageService.createStorageServiceInstance(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to create Storage Service instance");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}

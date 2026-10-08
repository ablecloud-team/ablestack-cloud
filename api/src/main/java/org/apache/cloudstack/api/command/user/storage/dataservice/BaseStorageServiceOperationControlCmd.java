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
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageServiceOperationResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

/** Control requests bypass the long writer queue; only operation-scoped CAS intent is changed. */
public abstract class BaseStorageServiceOperationControlCmd extends BaseCmd {
    @Inject private StorageService storageService;
    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, required = true, description = "Storage Service instance")
    private Long instanceId;
    @Parameter(name = "operationid", type = CommandType.UUID, entityType = StorageServiceOperationResponse.class, required = true, description = "Managed Storage Service operation UUID")
    private Long operationId;
    @Parameter(name = "expectedrevision", type = CommandType.LONG, description = "Expected operation_control controlRevision for CAS; not the desired configuration revision")
    private Long expectedRevision;
    @Parameter(name = "confirmation", type = CommandType.STRING, description = "Exact instance name confirming drain impact")
    private String confirmation;
    @Parameter(name = "maintenancewindow", type = CommandType.BOOLEAN, description = "Explicit maintenance-window acknowledgment for a service drain")
    private Boolean maintenanceWindow;
    public Long getInstanceId() { return instanceId; }
    public Long getOperationId() { return operationId; }
    public Long getExpectedRevision() { return expectedRevision; }
    public String getConfirmation() { return confirmation; }
    public Boolean getMaintenanceWindow() { return maintenanceWindow; }
    public abstract String getControlAction();
    @Override public long getEntityOwnerId() { return org.apache.cloudstack.context.CallContext.current().getCallingAccount().getId(); }
    @Override public void execute() {
        StorageServiceRuntimeResponse response = storageService.storageServiceOperationControl(this);
        response.setResponseName(getCommandName());setResponseObject(response);
    }
}

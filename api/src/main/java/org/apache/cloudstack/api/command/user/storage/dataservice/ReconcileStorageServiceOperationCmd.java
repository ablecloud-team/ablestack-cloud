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
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.StorageServiceOperationResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "reconcileStorageServiceOperation", responseObject = StorageServiceOperationResponse.class,
        description = "Verifies and reconciles a failed configuration operation without overwriting later successful revisions.", since = "4.23.0",
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ReconcileStorageServiceOperationCmd extends BaseStorageServiceAsyncCmd implements UserCmd {
    @Inject private StorageService storageService;
    @Parameter(name = "operationid", type = CommandType.UUID, entityType = StorageServiceOperationResponse.class, required = true)
    private Long operationId;
    public Long getOperationId() { return operationId; }
    public long getEntityOwnerId() { return org.apache.cloudstack.context.CallContext.current().getCallingAccountId(); }
    public String getEventType() { return "STORAGE.OPERATION.RECONCILE"; }
    public String getEventDescription() { return "Reconciling a failed Storage Service configuration operation"; }
    public void execute() {
        StorageServiceOperationResponse response = storageService.reconcileStorageServiceOperation(this);
        response.setResponseName(getCommandName());setResponseObject(response);
    }
}

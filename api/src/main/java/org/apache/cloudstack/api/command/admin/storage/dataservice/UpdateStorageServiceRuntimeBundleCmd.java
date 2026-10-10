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
package org.apache.cloudstack.api.command.admin.storage.dataservice;

import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.StorageServiceRuntimeBundleResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "updateStorageServiceRuntimeBundle", responseObject = StorageServiceRuntimeBundleResponse.class,
        description = "Verifies, publishes, disables, deprecates or revokes a runtime catalog bundle.", since = "4.23.0",
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, authorized = {RoleType.Admin})
public class UpdateStorageServiceRuntimeBundleCmd extends BaseAsyncCmd implements AdminCmd {
    @Inject private StorageService storageService;
    @Parameter(name = "id", type = CommandType.UUID, entityType = StorageServiceRuntimeBundleResponse.class, required = true) private Long id;
    public Long getId() { return id; }
    @Parameter(name = "state", type = CommandType.STRING, required = true) private String state;
    @Parameter(name = "expectedstate", type = CommandType.STRING, required = true) private String expectedState;
    @Parameter(name = "reason", type = CommandType.STRING, required = true) private String reason;
    public String getState() { return state; }
    public String getExpectedState() { return expectedState; }
    public String getReason() { return reason; }
    public long getEntityOwnerId() { return 0; }
    public String getEventType() { return "STORAGE.SERVICE.RUNTIME.CATALOG.UPDATE"; }
    public String getEventDescription() { return "Changing runtime bundle " + id + " to " + state; }
    public void execute() {
        StorageServiceRuntimeBundleResponse response = storageService.updateStorageServiceRuntimeBundle(this);
        response.setResponseName(getCommandName()); setResponseObject(response);
    }
}

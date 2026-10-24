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
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageServiceConfigurationOperationResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;
@APICommand(name = "listStorageServiceConfigurationOperations", responseObject = StorageServiceConfigurationOperationResponse.class,
        description = "List a SharedFS configuration operation without submitting new configuration",
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ListStorageServiceConfigurationOperationsCmd extends BaseListCmd implements UserCmd {
    @Inject private StorageService storageService;
    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class,
            required = true, description = "Storage Service instance ID")
    private Long instanceId;
    @Parameter(name = "id", type = CommandType.STRING, required = false, description = "Operation UUID")
    private String id;
    @Parameter(name = "requestid", type = CommandType.STRING, description = "Logical request UUID")
    private String requestId;
    @Parameter(name = "resourceid", type = CommandType.STRING, description = "Resource UUID")
    private String resourceId;
    @Parameter(name = "jobid", type = CommandType.UUID, entityType = org.apache.cloudstack.api.response.AsyncJobResponse.class,
            description = "Original asynchronous job ID")
    private Long jobId;
    public Long getJobId() { return jobId; }
    public Long getInstanceId() { return instanceId; }
    public String getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getResourceId() { return resourceId; }
    @Override public void execute() {
        org.apache.cloudstack.api.response.ListResponse<StorageServiceConfigurationOperationResponse> response = storageService.listStorageServiceConfigurationOperations(this);
        response.setResponseName(getCommandName()); setResponseObject(response);
    }
}

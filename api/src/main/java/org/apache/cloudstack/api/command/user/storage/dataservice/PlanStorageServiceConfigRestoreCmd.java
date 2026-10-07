// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
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
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.storage.dataservice.StorageService;
import org.apache.cloudstack.storage.dataservice.StorageConfigRequest;
import org.apache.cloudstack.api.response.StorageServiceConfigArtifactResponse;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;

@APICommand(name = "planStorageServiceConfigRestore", responseObject = StorageServiceConfigArtifactResponse.class,
        description = "Storage Service configuration plan with scoped artifacts and explicit resource mapping.", since = "4.23.0",
        requestHasSensitiveInfo = true, responseHasSensitiveInfo = true,
        authorized = {RoleType.Admin})
public class PlanStorageServiceConfigRestoreCmd extends BaseStorageServiceAsyncCmd implements UserCmd, StorageConfigRequest {
    @Inject private StorageService storageService;
    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, required = true) private Long instanceId;
    public Long getInstanceId() { return instanceId; }
    @Parameter(name = "artifactid", type = CommandType.UUID, entityType = StorageServiceConfigArtifactResponse.class) private Long artifactId;
    public Long getArtifactId() { return artifactId; }
    @Parameter(name = "mapping", type = CommandType.STRING, length = 262144) private String mapping;
    public String getMapping() { return mapping; }
    @Parameter(name = "targetmode", type = CommandType.STRING, length = 255) private String targetMode;
    public String getTargetMode() { return targetMode; }
    @Parameter(name = "targetinstanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class) private Long targetInstanceId;
    public Long getTargetInstanceId() { return targetInstanceId; }
    public BaseCmd getBaseCmd() { return this; }
    public String getConfigAction() { return "PLAN"; }
    public long getEntityOwnerId() { return 0; }
    public String getEventType() { return "STORAGE.CONFIG.PLAN"; }
    public String getEventDescription() { return "Storage Service configuration plan"; }
    public void execute() {
        StorageServiceConfigArtifactResponse response = storageService.storageServiceConfiguration(this);
        response.setResponseName(getCommandName());response.setObjectName("storageserviceconfiguration");setResponseObject(response);
    }
}

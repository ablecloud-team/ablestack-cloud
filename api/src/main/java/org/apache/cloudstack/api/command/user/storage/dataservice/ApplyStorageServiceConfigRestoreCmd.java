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

@APICommand(name = "applyStorageServiceConfigRestore", responseObject = StorageServiceConfigArtifactResponse.class,
        description = "Storage Service configuration apply with scoped artifacts and explicit resource mapping.", since = "4.23.0",
        requestHasSensitiveInfo = true, responseHasSensitiveInfo = true,
        authorized = {RoleType.Admin})
public class ApplyStorageServiceConfigRestoreCmd extends BaseStorageServiceAsyncCmd implements UserCmd, StorageConfigRequest {
    @Inject private StorageService storageService;
    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, required = true) private Long instanceId;
    public Long getInstanceId() { return instanceId; }
    @Parameter(name = "artifactid", type = CommandType.UUID, entityType = StorageServiceConfigArtifactResponse.class) private Long artifactId;
    public Long getArtifactId() { return artifactId; }
    @Parameter(name = "plantoken", type = CommandType.STRING, length = 255) private String planToken;
    public String getPlanToken() { return planToken; }
    @Parameter(name = "confirmation", type = CommandType.STRING, length = 255) private String confirmation;
    public String getConfirmation() { return confirmation; }
    @Parameter(name = "credentials", type = CommandType.STRING, length = 262144) private String credentials;
    public String getCredentials() { return credentials; }
    public BaseCmd getBaseCmd() { return this; }
    public String getConfigAction() { return "APPLY"; }
    public long getEntityOwnerId() { return 0; }
    public String getEventType() { return "STORAGE.CONFIG.APPLY"; }
    public String getEventDescription() { return "Storage Service configuration apply"; }
    @Override
    public void validateSpecificParameters(final java.util.Map<String, String> params) {
        super.validateSpecificParameters(params);
        if (!com.cloud.utils.crypt.EncryptionSecretKeyChecker.useEncryption()) {
            throw new com.cloud.exception.InvalidParameterValueException("Protected configuration transfer requires management encryption before queueing");
        }
    }
    public void execute() {
        StorageServiceConfigArtifactResponse response = storageService.storageServiceConfiguration(this);
        response.setResponseName(getCommandName());response.setObjectName("storageserviceconfiguration");setResponseObject(response);
    }
}

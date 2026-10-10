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
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageServiceConfigArtifactResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd;
import org.apache.cloudstack.storage.dataservice.StorageService;
import org.apache.cloudstack.storage.dataservice.StorageConfigRequest;

@APICommand(name="applyStorageServiceApprovedMaintenance",responseObject=StorageServiceRuntimeResponse.class,
        description="Applies one reviewed configuration plan during explicitly approved service downtime.",since="4.23.0",authorized={RoleType.Admin},requestHasSensitiveInfo=true,responseHasSensitiveInfo=false)
public class ApplyStorageServiceApprovedMaintenanceCmd extends BaseStorageServiceAsyncCmd implements StorageConfigRequest {
    @Inject private StorageService service;
    @Parameter(name="instanceid",type=CommandType.UUID,entityType=StorageServiceInstanceResponse.class,required=true) private Long instanceId;
    @Parameter(name="artifactid",type=CommandType.UUID,entityType=StorageServiceConfigArtifactResponse.class,required=true) private Long artifactId;
    @Parameter(name="confirmation",type=CommandType.STRING,required=true) private String confirmation;
    @Parameter(name="maintenancewindow",type=CommandType.BOOLEAN,required=true) private Boolean maintenanceWindow;
    @Parameter(name="plantoken",type=CommandType.STRING,length=131072,required=true) private String planToken;
    @Parameter(name="credentials",type=CommandType.STRING,length=131072) private String credentials;
    @Override public void validateSpecificParameters(java.util.Map<String,String> params) {
        super.validateSpecificParameters(params);
        if((params.containsKey("credentials")||params.containsKey("plantoken"))&&!com.cloud.utils.crypt.EncryptionSecretKeyChecker.useEncryption())throw new com.cloud.exception.InvalidParameterValueException("Reviewed maintenance tokens and credentials require encrypted persistent async requests");
    }
    public Long getInstanceId(){return instanceId;}
    public Long getArtifactId(){return artifactId;}
    public String getConfirmation(){return confirmation;}
    public Boolean getMaintenanceWindow(){return maintenanceWindow;}
    public String getPlanToken(){return planToken;}
    public String getCredentials(){return credentials;}
    public org.apache.cloudstack.api.BaseCmd getBaseCmd(){return this;}
    public String getConfigAction(){return "APPLY_RESTORE";}
    public long getEntityOwnerId(){return org.apache.cloudstack.context.CallContext.current().getCallingAccountId();}
    public String getEventType(){return "STORAGE.SERVICE.MAINTENANCE";}
    public String getEventDescription(){return "Applying a reviewed service configuration with explicit downtime approval";}
    public void execute(){StorageServiceRuntimeResponse response=service.applyStorageServiceApprovedMaintenance(this);response.setResponseName(getCommandName());response.setObjectName("storageserviceruntime");setResponseObject(response);}
}

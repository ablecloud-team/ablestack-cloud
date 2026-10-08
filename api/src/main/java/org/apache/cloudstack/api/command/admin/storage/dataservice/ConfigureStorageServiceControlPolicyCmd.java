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
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name="configureStorageServiceControlPolicy",responseObject=StorageServiceRuntimeResponse.class,
        description="Explicitly opt one compatible instance into logical resource control.",since="4.23.0",
        requestHasSensitiveInfo=false,responseHasSensitiveInfo=false,authorized={RoleType.Admin})
public class ConfigureStorageServiceControlPolicyCmd extends BaseStorageServiceAsyncCmd {
    @Inject private StorageService storageService;
    @Parameter(name="instanceid",type=CommandType.UUID,entityType=StorageServiceInstanceResponse.class,required=true)
    private Long instanceId;
    @Parameter(name="enabled",type=CommandType.BOOLEAN,required=true) private Boolean enabled;
    @Parameter(name="expectedpolicyrevision",type=CommandType.LONG,required=true) private Long expectedPolicyRevision;
    @Parameter(name="confirmation",type=CommandType.STRING,required=true,description="Exact instance name authorizing this control policy") private String confirmation;
    public Long getInstanceId() {return instanceId;}
    public Boolean getEnabled() {return enabled;}
    public Long getExpectedPolicyRevision() {return expectedPolicyRevision;}
    public String getConfirmation() {return confirmation;}
    public String getEventType() {return "STORAGE.SERVICE.CONTROL.POLICY";}
    public String getEventDescription() {return "Configure scoped Storage Service resource control";}
    public void execute() {StorageServiceRuntimeResponse response=storageService.configureStorageServiceControlPolicy(this);response.setResponseName(getCommandName());response.setObjectName("storageserviceruntime");setResponseObject(response);}
}

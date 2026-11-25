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
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageSmbServiceNameResponse;
import org.apache.cloudstack.api.command.user.UserCmd;
@APICommand(name="listStorageSmbServiceNames",responseObject=StorageSmbServiceNameResponse.class,
 description="List registered SMB AD names; optional bounded read-only refresh",requestHasSensitiveInfo=false,responseHasSensitiveInfo=false,
 authorized={RoleType.Admin,RoleType.ResourceAdmin,RoleType.DomainAdmin,RoleType.User})
public class ListStorageSmbServiceNamesCmd extends BaseListCmd implements UserCmd {
    @Inject private org.apache.cloudstack.storage.dataservice.StorageService storageService;
    @Parameter(name="instanceid",type=CommandType.UUID,entityType=StorageServiceInstanceResponse.class,required=true,description="Storage Service instance") private Long instanceId;
    @Parameter(name="refresh",type=CommandType.BOOLEAN,description="Read-only AD/DNS observation; no configuration changes") private Boolean refresh;
    public Long getInstanceId(){return instanceId;}
    public boolean isRefresh(){return Boolean.TRUE.equals(refresh);}
    @Override public void execute(){
        ListResponse<StorageSmbServiceNameResponse> response=storageService.listStorageSmbServiceNames(this);
        response.setResponseName(getCommandName());setResponseObject(response);
    }
}

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
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageSmbServiceNameContextResponse;
import org.apache.cloudstack.api.command.user.UserCmd;
@APICommand(name="getStorageSmbServiceNameContext",responseObject=StorageSmbServiceNameContextResponse.class,
 description="Read joined AD computer context without modifying DNS, SPNs, accounts or configuration",
 requestHasSensitiveInfo=false,responseHasSensitiveInfo=false,
 authorized={RoleType.Admin,RoleType.ResourceAdmin,RoleType.DomainAdmin,RoleType.User})
public class GetStorageSmbServiceNameContextCmd extends BaseCmd implements UserCmd {
    @Inject private org.apache.cloudstack.storage.dataservice.StorageService storageService;
    @Parameter(name="instanceid",type=CommandType.UUID,entityType=StorageServiceInstanceResponse.class,required=true,description="Storage Service instance")
    private Long instanceId;
    public Long getInstanceId(){return instanceId;}
    @Override public long getEntityOwnerId(){return 0;}
    @Override public void execute(){
        StorageSmbServiceNameContextResponse response=storageService.getStorageSmbServiceNameContext(this);
        response.setResponseName(getCommandName());setResponseObject(response);
    }
}

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
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageSmbServiceNameResponse;
import org.apache.cloudstack.api.command.user.UserCmd;
public abstract class BaseStorageSmbServiceNameCmd extends BaseStorageServiceConfigurationCmd implements UserCmd {
    @Inject private org.apache.cloudstack.storage.dataservice.StorageService storageService;
    @Parameter(name="instanceid",type=CommandType.UUID,entityType=StorageServiceInstanceResponse.class,required=true,description="Storage Service instance") private Long instanceId;
    @Parameter(name="id",type=CommandType.UUID,entityType=StorageSmbServiceNameResponse.class,description="Existing service name") private Long id;
    @Parameter(name="fqdn",type=CommandType.STRING,length=253,description="FQDN within the joined AD DNS zone") private String fqdn;
    @Parameter(name="kind",type=CommandType.STRING,length=16,description="ALIAS or immutable BASE registration") private String kind;
    @Parameter(name="endpointid",type=CommandType.STRING,length=40,description="Ready SMB endpoint; omitted preserves the binding") private String endpointId;
    @Parameter(name="username",type=CommandType.STRING,length=255,required=true,description="One-use delegated AD operator; never persisted") private String username;
    @Parameter(name="password",type=CommandType.STRING,length=255,required=true,description="One-use AD credential; never persisted") private String password;
    protected void initializeEnrollment(long instance,String name,String endpoint,String user,String secret,
            long revision,String request,String ack,Object parentJob){
        instanceId=instance;fqdn=name;endpointId=endpoint;kind="ALIAS";username=user;password=secret;
        initializeConfigurationAttempt(request,revision,ack);setJob(parentJob);
    }
    protected void initializeReconcile(long instance,long nameId,String name,String nameKind,String user,String secret,
            long revision,String request,String ack,Object parentJob){
        initializeEnrollment(instance,name,null,user,secret,revision,request,ack,parentJob);id=nameId;kind=nameKind;
    }
    public Long getInstanceId(){return instanceId;}
    public Long getId(){return id;}
    public String getFqdn(){return fqdn;}
    public String getKind(){return kind;}
    public String getEndpointId(){return endpointId;}
    public String getUsername(){return username;}
    public String getPassword(){return password;}
    @Override public String getEventType(){return "STORAGE.SMB.NAME.CHANGE";}
    @Override public String getEventDescription(){return "Change owned SMB AD service name";}
    @Override public long getEntityOwnerId(){return 0;}
    @Override public void execute(){
        StorageSmbServiceNameResponse response=storageService.mutateStorageSmbServiceName(this);
        response.setResponseName(getCommandName());setResponseObject(response);
    }
}

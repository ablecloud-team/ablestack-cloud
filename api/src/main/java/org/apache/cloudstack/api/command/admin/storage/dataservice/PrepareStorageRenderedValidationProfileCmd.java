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
import org.apache.cloudstack.api.*;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.response.*;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd;
import org.apache.cloudstack.storage.dataservice.StorageService;
@APICommand(name="prepareStorageRenderedValidationProfile",responseObject=StorageServiceRuntimeResponse.class,
    description="Approves the reviewed disposable four-DATA validation fixture using fresh signed runtime and server protected evidence.",since="4.23.0",
    requestHasSensitiveInfo=false,responseHasSensitiveInfo=false,authorized={RoleType.Admin})
public class PrepareStorageRenderedValidationProfileCmd extends BaseStorageServiceAsyncCmd {
    @Inject private StorageService service;
    @Parameter(name="instanceid",type=CommandType.UUID,entityType=StorageServiceInstanceResponse.class,required=true) private Long instanceId;
    @Parameter(name="confirmation",type=CommandType.STRING,required=true) private String confirmation;
    @Parameter(name="expectedprofilerevision",type=CommandType.LONG,required=true) private Long expectedProfileRevision;
    @Parameter(name="previewtoken",type=CommandType.STRING,required=true) private String previewToken;
    @Parameter(name="expiresinhours",type=CommandType.INTEGER) private Integer expiresInHours;
    public Long getInstanceId(){return instanceId;} public String getConfirmation(){return confirmation;}
    public Long getExpectedProfileRevision(){return expectedProfileRevision;} public String getPreviewToken(){return previewToken;}
    public Integer getExpiresInHours(){return expiresInHours==null?4:expiresInHours;}
    public long getEntityOwnerId(){return org.apache.cloudstack.context.CallContext.current().getCallingAccountId();}
    public String getEventType(){return "STORAGE.RENDERED.VALIDATION.PREPARE";}
    public String getEventDescription(){return "Approving a reviewed disposable new SPARSE validation fixture";}
    public void execute(){StorageServiceRuntimeResponse r=service.prepareStorageRenderedValidationProfile(this);r.setResponseName(getCommandName());r.setObjectName("storageserviceruntime");setResponseObject(r);}
}

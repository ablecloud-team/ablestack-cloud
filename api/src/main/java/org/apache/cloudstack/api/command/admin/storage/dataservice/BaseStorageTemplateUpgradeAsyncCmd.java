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
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd;
import org.apache.cloudstack.storage.dataservice.StorageTemplateUpgradeRequest;
public abstract class BaseStorageTemplateUpgradeAsyncCmd extends BaseStorageServiceAsyncCmd implements AdminCmd, StorageTemplateUpgradeRequest {
    @javax.inject.Inject protected org.apache.cloudstack.storage.dataservice.StorageService storageService;
    @Parameter(name="sharedfilesystemid", type=CommandType.UUID, entityType=org.apache.cloudstack.api.response.SharedFSResponse.class, required=true)
    private Long sharedFileSystemId;
    @Parameter(name="upgradeid", type=CommandType.UUID, entityType=org.apache.cloudstack.api.response.StorageServiceTemplateUpgradeResponse.class)
    private Long upgradeId;
    @Parameter(name="templateid", type=CommandType.UUID, entityType=org.apache.cloudstack.api.response.TemplateResponse.class)
    private Long templateId;
    @Parameter(name="confirmation", type=CommandType.STRING, description="Exact SharedFS name approving the maintenance interruption")
    private String confirmation;
    @Parameter(name="maintenancewindow", type=CommandType.BOOLEAN, description="Explicitly authorize interrupted sessions in this maintenance window")
    private Boolean maintenanceWindow;
    public Long getSharedFileSystemId(){return sharedFileSystemId;}
    public Long getUpgradeId(){return upgradeId;}
    public Long getTemplateId(){return templateId;}
    public String getConfirmation(){return confirmation;}
    public Boolean getMaintenanceWindow(){return maintenanceWindow;}
    public org.apache.cloudstack.api.BaseCmd getBaseCmd(){return this;}
    public long getEntityOwnerId(){return 0;}
    public void execute(){
        org.apache.cloudstack.api.response.StorageServiceTemplateUpgradeResponse response=storageService.storageServiceTemplateUpgrade(this);
        response.setResponseName(getCommandName());response.setObjectName("storageservicetemplateupgrade");setResponseObject(response);
    }
    public String getEventType(){return "STORAGE.SERVICE.TEMPLATE."+getTemplateAction();}
    public String getEventDescription(){return "SharedFS SystemVM template "+getTemplateAction();}
}

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
// specific language govening permissions and limitations
// under the License.

package org.apache.cloudstack.api.command.user.storage.dataservice;
import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.*;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.*;
import org.apache.cloudstack.storage.dataservice.StorageService;
@APICommand(name = "createStorageDirectoryPolicy", responseObject = StorageDirectoryPolicyResponse.class,
 description = "Register existing directory metadata without modifying file permissions",
 requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
 authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class CreateStorageDirectoryPolicyCmd extends BaseStorageServiceConfigurationCmd implements UserCmd {
 @Inject private StorageService storageService;
 @Parameter(name="instanceid",type=CommandType.UUID,entityType=StorageServiceInstanceResponse.class,required=true,description="Storage Service instance")
 private Long instanceId;
 public Long getInstanceId() { return instanceId; }
 @Parameter(name="volumeid",type=CommandType.UUID,entityType=VolumeResponse.class,required=true,description="Existing attached backing volume") private Long volumeId;
 public Long getVolumeId() { return volumeId; }
 @Parameter(name="path",type=CommandType.STRING,length=4096,required=true,description="Existing share backing path") private String path;
 public String getPath() { return path; }
 @Override public String getEventType() { return "STORAGE.DIRECTORY.CREATE"; }
 @Override public String getEventDescription() { return "Create shared directory policy"; }
 @Override public long getEntityOwnerId() { return 0; }
 @Override public void execute() {
  StorageDirectoryPolicyResponse value=storageService.createStorageDirectoryPolicy(this);
  value.setResponseName(getCommandName()); setResponseObject(value);
 }
}

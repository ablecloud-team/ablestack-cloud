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
@APICommand(name = "updateStorageDirectoryPolicy", responseObject = StorageDirectoryPolicyResponse.class,
 description = "Update a shared NFS/SMB directory POSIX policy",
 requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
 authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class UpdateStorageDirectoryPolicyCmd extends BaseStorageServiceConfigurationCmd implements UserCmd {
 @Inject private StorageService storageService;
 @Parameter(name="instanceid",type=CommandType.UUID,entityType=StorageServiceInstanceResponse.class,required=true,description="Storage Service instance")
 private Long instanceId;
 public Long getInstanceId() { return instanceId; }
 @Parameter(name="id",type=CommandType.UUID,entityType=StorageDirectoryPolicyResponse.class,required=true,description="Directory policy") private Long id;
 public Long getId() { return id; }
 @Parameter(name="expectedpolicyrevision",type=CommandType.LONG,required=true,description="Observed directory policy revision") private Long expectedPolicyRevision;
 public Long getExpectedPolicyRevision() { return expectedPolicyRevision; }
 @Parameter(name="owneruid",type=CommandType.INTEGER,description="Changed owner UID only") private Integer ownerUid;
 public Integer getOwnerUid() { return ownerUid; }
 @Parameter(name="ownergid",type=CommandType.INTEGER,description="Changed owner GID only") private Integer ownerGid;
 public Integer getOwnerGid() { return ownerGid; }
 @Parameter(name="mode",type=CommandType.STRING,description="Changed octal mode only") private String mode;
 public String getMode() { return mode; }
 @Parameter(name="recursivepermission",type=CommandType.BOOLEAN,description="Explicit recursive apply; default false") private Boolean recursivePermission;
 public Boolean getRecursivePermission() { return recursivePermission; }
 @Override public String getEventType() { return "STORAGE.DIRECTORY.UPDATE"; }
 @Override public String getEventDescription() { return "Update shared directory policy"; }
 @Override public long getEntityOwnerId() { return 0; }
 @Override public void execute() {
  StorageDirectoryPolicyResponse value=storageService.updateStorageDirectoryPolicy(this);
  value.setResponseName(getCommandName()); setResponseObject(value);
 }
}

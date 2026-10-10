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
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.SharedFSResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeBundleResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeUpgradeResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "preflightStorageServiceRuntimeUpgrade", responseObject = StorageServiceRuntimeUpgradeResponse.class,
        description = "Stages and preflights a signed Storage Service runtime bundle.", since = "4.23.0",
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, authorized = {RoleType.Admin})
public class PreflightStorageServiceRuntimeUpgradeCmd extends BaseStorageServiceAsyncCmd implements AdminCmd {
    @Inject private StorageService storageService;
    @Parameter(name = "sharedfilesystemid", type = CommandType.UUID, entityType = SharedFSResponse.class, required = true) private Long sharedFileSystemId;
    @Parameter(name = "bundleid", type = CommandType.UUID, entityType = StorageServiceRuntimeBundleResponse.class, required = true) private Long bundleId;
    public Long getSharedFileSystemId() { return sharedFileSystemId; }
    public Long getBundleId() { return bundleId; }

    public String getEventType() { return "STORAGE.SERVICE.RUNTIME.PREFLIGHT"; }
    public String getEventDescription() { return "Preflighting Storage Service runtime bundle " + bundleId; }
    public void execute() { final StorageServiceRuntimeUpgradeResponse response = storageService.preflightStorageServiceRuntimeUpgrade(this); response.setResponseName(getCommandName()); setResponseObject(response); }
}

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
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeBundleResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "listStorageServiceRuntimeBundles", responseObject = StorageServiceRuntimeBundleResponse.class,
        description = "Lists registered Storage Service runtime bundles.", since = "4.23.0",
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, authorized = {RoleType.Admin})
public class ListStorageServiceRuntimeBundlesCmd extends BaseListCmd implements AdminCmd {
    @Inject private StorageService storageService;
    @Parameter(name = "id", type = CommandType.UUID, entityType = StorageServiceRuntimeBundleResponse.class) private Long id;
    @Parameter(name = "version", type = CommandType.STRING) private String version;
    @Parameter(name = "catalog", type = CommandType.BOOLEAN) private Boolean catalog;
    public boolean isCatalog() { return Boolean.TRUE.equals(catalog); }
    public Long getId() { return id; }
    public String getVersion() { return version; }
    public void execute() { final ListResponse<StorageServiceRuntimeBundleResponse> response = storageService.listStorageServiceRuntimeBundles(this); response.setResponseName(getCommandName()); setResponseObject(response); }
}

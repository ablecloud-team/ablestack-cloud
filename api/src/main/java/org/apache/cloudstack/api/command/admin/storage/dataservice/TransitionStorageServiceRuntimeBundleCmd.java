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
import org.apache.cloudstack.api.*;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.*;
import org.apache.cloudstack.storage.dataservice.StorageServiceRuntimeCatalog;

@APICommand(name = "transitionStorageServiceRuntimeBundle", responseObject = StorageServiceRuntimeBundleResponse.class, description = "Manages the global signed runtime catalogue.", since = "4.23.0", authorized = {RoleType.Admin}, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class TransitionStorageServiceRuntimeBundleCmd extends BaseAsyncCmd implements AdminCmd {
    @Inject private StorageServiceRuntimeCatalog catalog;
    @Parameter(name = "bundleid", type = CommandType.UUID, entityType = StorageServiceRuntimeBundleResponse.class, required = true) private Long bundleId;
    public Long getBundleId() { return bundleId; }
    @Parameter(name = "requestid", type = CommandType.STRING, required = true) private String requestId;
    @Parameter(name = "expectedrevision", type = CommandType.LONG, required = true) private Long expectedRevision;
    public String getRequestId() { return requestId; }
    public Long getExpectedRevision() { return expectedRevision; }
    @Parameter(name = "action", type = CommandType.STRING, required = true) private String action;
    @Parameter(name = "reason", type = CommandType.STRING, required = true) private String reason;
    public String getAction() { return action; }
    public String getReason() { return reason; }
    public long getEntityOwnerId() { return 0; }
    public String getEventType() { return "STORAGE.SERVICE.RUNTIME.CATALOG"; }
    public String getEventDescription() { return "Runtime catalogue transition " + bundleId; }
    public void execute() { StorageServiceRuntimeBundleResponse response = catalog.transition(this); response.setResponseName(getCommandName()); setResponseObject(response); }
}

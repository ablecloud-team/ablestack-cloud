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
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.storage.dataservice.StorageService;

/** Uses CloudStack's persistent per-instance async queue for all Storage Service writers. */
public abstract class BaseStorageServiceAsyncCmd extends BaseAsyncCmd {
    @Inject private StorageService storageServiceScope;
    @Parameter(name = "idempotencykey", type = CommandType.STRING, description = "Stable retry key for one Storage Service change")
    private String idempotencyKey;
    @Parameter(name = "expectedrevision", type = CommandType.LONG, description = "Expected last committed configuration revision")
    private Long expectedRevision;

    @Parameter(name = "admaintenancewindow", type = CommandType.BOOLEAN, description = "Explicit approval for interrupted sessions when changing a joined AD service")
    private Boolean adMaintenanceWindow;
    @Parameter(name = "adconfirmation", type = CommandType.STRING, length = 255, description = "Exact joined Storage Service instance name approving the AD maintenance window")
    private String adConfirmation;
    public Boolean getAdMaintenanceWindow() { return adMaintenanceWindow; }
    public String getAdConfirmation() { return adConfirmation; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Long getExpectedRevision() { return expectedRevision; }
    @Override public long getEntityOwnerId() { return org.apache.cloudstack.context.CallContext.current().getCallingAccount().getId(); }
    @Override public String getSyncObjType() { return "StorageServiceInstance"; }
    @Override public Long getSyncObjId() { return storageServiceScope.getStorageServiceSyncId(this); }
}

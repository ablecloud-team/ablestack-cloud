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
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.SharedFSResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "repairStorageServiceNicIdentity",
        responseObject = StorageServiceRuntimeResponse.class,
        description = "Dry-runs or repairs a Storage Service NIC primary identity using guarded runtime evidence.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin})
public class RepairStorageServiceNicIdentityCmd extends BaseAsyncCmd implements AdminCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = "sharedfilesystemid", type = CommandType.UUID, entityType = SharedFSResponse.class, required = true,
            description = "Shared FileSystem ID")
    private Long sharedFileSystemId;

    @Parameter(name = "dryrun", type = CommandType.BOOLEAN,
            description = "when true, returns repair eligibility without changing the database")
    private Boolean dryRun;

    @Parameter(name = "expectedruntimeprimary", type = CommandType.STRING,
            description = "runtime primary IPv4 that must still be observed when applying a repair")
    private String expectedRuntimePrimary;

    public Long getSharedFileSystemId() {
        return sharedFileSystemId;
    }

    public boolean isDryRun() {
        return dryRun == null || dryRun;
    }

    public String getExpectedRuntimePrimary() {
        return expectedRuntimePrimary;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.SERVICE.NIC.IDENTITY.REPAIR";
    }

    @Override
    public String getEventDescription() {
        return (isDryRun() ? "Dry-running" : "Repairing") + " Storage Service NIC identity for Shared FileSystem " + sharedFileSystemId;
    }

    @Override
    public void execute() {
        final StorageServiceRuntimeResponse response = storageService.repairStorageServiceNicIdentity(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to evaluate Storage Service NIC identity");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}

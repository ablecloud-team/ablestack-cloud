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
package org.apache.cloudstack.api.command.user.snapshot;

import com.cloud.storage.Snapshot;
import com.cloud.user.Account;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.SnapshotCopyTargetsResponse;
import org.apache.cloudstack.api.response.SnapshotResponse;
import org.apache.cloudstack.api.response.ZoneResponse;

@APICommand(name = "listSnapshotCopyTargets", description = "Lists source support and destination Secondary copy availability without creating a job or changing copies.",
        responseObject = SnapshotCopyTargetsResponse.class, since = "4.23.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User},
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class ListSnapshotCopyTargetsCmd extends BaseCmd implements UserCmd {
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = SnapshotResponse.class,
            required = true, description = "Snapshot UUID")
    private Long id;
    @Parameter(name = ApiConstants.SOURCE_ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class,
            description = "Source Zone UUID; defaults to the original snapshot Zone")
    private Long sourceZoneId;

    @Override public void execute() {
        SnapshotCopyTargetsResponse response = _snapshotService.previewSnapshotCopy(id, sourceZoneId);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
    @Override public long getEntityOwnerId() {
        Snapshot snapshot = _entityMgr.findById(Snapshot.class, id);
        return snapshot == null ? Account.ACCOUNT_ID_SYSTEM : snapshot.getAccountId();
    }
}

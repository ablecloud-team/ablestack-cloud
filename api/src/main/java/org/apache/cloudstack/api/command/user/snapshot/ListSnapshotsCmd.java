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

import java.util.List;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListTaggedResourcesCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.SnapshotResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.api.response.ZoneResponse;

import com.cloud.storage.Snapshot;

@APICommand(name = "listSnapshots", description = "Lists all available Snapshots for the Account.", responseObject = SnapshotResponse.class, entityType = {
        Snapshot.class }, responseView = ResponseObject.ResponseView.Restricted, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class ListSnapshotsCmd extends BaseListTaggedResourcesCmd {
    @Parameter(name = "includesnapshotcontext", type = CommandType.BOOLEAN,
            description = "Include current source volume and Instance snapshot-operation context for safe UI preflight")
    private Boolean includeSnapshotContext;

    public boolean isIncludeSnapshotContext() { return Boolean.TRUE.equals(includeSnapshotContext); }



    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = SnapshotResponse.class, description = "Lists Snapshot by Snapshot ID")
    private Long id;

    @Parameter(name=ApiConstants.IDS, type=CommandType.LIST, collectionType=CommandType.UUID, entityType=SnapshotResponse.class, description = "The IDs of the Snapshots, mutually exclusive with id", since = "4.9")
    private List<Long> ids;

    @Parameter(name = ApiConstants.INTERVAL_TYPE, type = CommandType.STRING, description = "Recurring interval: HOURLY, DAILY, WEEKLY or MONTHLY. Applies with or without volumeid and intersects snapshottype=RECURRING. Cannot be combined with MANUAL.")
    private String intervalType;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, description = "Lists Snapshot by Snapshot name")
    private String snapshotName;

    @Parameter(name = ApiConstants.SNAPSHOT_TYPE, type = CommandType.STRING, description = "Valid values are MANUAL or RECURRING.")
    private String snapshotType;

    @Parameter(name = ApiConstants.VOLUME_ID, type = CommandType.UUID, entityType = VolumeResponse.class, description = "The ID of the disk volume")
    private Long volumeId;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class, description = "List Snapshots by zone id")
    private Long zoneId;

    @Parameter(name = ApiConstants.SHOW_UNIQUE, type = CommandType.BOOLEAN, description = "Default true: one logical snapshot per entry/count. False: one snapshot/datastore reference per entry/count across zones; missing datastore references are explicitly Unknown", since = "4.19.0")
    private Boolean showUnique;

    @Parameter(name = ApiConstants.LOCATION_TYPE, type = CommandType.STRING, description = "list snapshots by location type. Used only when showunique=false. " +
            "Valid location types: 'primary', 'secondary'. Default is empty", since = "4.19.0")
    private String locationType;

    @Parameter(name = "sortkey", type = CommandType.STRING, description = "Server sort field: created (default), id, name, state, volumename, intervaltype, virtualsize, account, domain or project. Copy-dependent fields are not supported.")
    private String sortKey;

    @Parameter(name = "sortorder", type = CommandType.STRING, description = "Server sort direction: asc or desc (default). Ties use snapshot id in the same direction, then datastore reference ascending.")
    private String sortOrder;

    public String getSortKey() { return sortKey; }

    public String getSortOrder() { return sortOrder; }

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    public String getIntervalType() {
        return intervalType;
    }

    public String getSnapshotName() {
        return snapshotName;
    }

    public String getSnapshotType() {
        return snapshotType;
    }

    public Long getVolumeId() {
        return volumeId;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public boolean isShowUnique() {
        if (Boolean.FALSE.equals(showUnique)) {
            return false;
        }
        return true;
    }

    public String getLocationType() {
        if (!isShowUnique()) {
            return locationType;
        }
        return null;
    }

    public Long getImageStoreId() {
        return null;
    }

    public Long getStoragePoolId() {
        return null;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.Snapshot;
    }

    @Override
    public void execute() {
        ListResponse<SnapshotResponse> response = _queryService.listSnapshots(this);
        response.setResponseName(getCommandName());

        setResponseObject(response);
    }

    public List<Long> getIds() {
        return ids;
    }
}

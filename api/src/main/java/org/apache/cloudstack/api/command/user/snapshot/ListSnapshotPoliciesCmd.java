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

import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.acl.RoleType;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListProjectAndAccountResourcesCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.SnapshotPolicyResponse;
import org.apache.cloudstack.api.response.VolumeResponse;

import com.cloud.storage.snapshot.SnapshotPolicy;
import com.cloud.utils.Pair;

@APICommand(name = "listSnapshotPolicies", description = "Lists Snapshot policies.", responseObject = SnapshotPolicyResponse.class,
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class ListSnapshotPoliciesCmd extends BaseListProjectAndAccountResourcesCmd {


    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.VOLUME_ID, type = CommandType.UUID, entityType = VolumeResponse.class, description = "The ID of the disk volume")
    private Long volumeId;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = SnapshotPolicyResponse.class, description = "The ID of the Snapshot policy")
    private Long id;

    @Parameter(name = ApiConstants.FOR_DISPLAY, type = CommandType.BOOLEAN, description = "List resources by display flag; only ROOT admin is eligible to pass this parameter", since = "4.4", authorized = {RoleType.Admin})
    private Boolean display;

    @Parameter(name = "ignoreproject", type = CommandType.BOOLEAN, description = "Exclude project-owned policies, including ID lookups. Cannot be combined with projectid. Defaults to false.")
    private Boolean ignoreProject;

    @Parameter(name = "sortkey", type = CommandType.STRING, description = "Server sort field: id (default), intervaltype, maxsnaps, schedule, timezone or volumename. Schedule uses local time of day (hourly minutes use hour zero), then interval and weekday/day. This is not next execution time.")
    private String sortKey;

    @Parameter(name = "sortorder", type = CommandType.STRING, description = "Sort direction: asc or desc (default). Equal values use policy ID in the same direction.")
    private String sortOrder;

    @Parameter(name = "includepolicycontext", type = CommandType.BOOLEAN, description = "Include ACL-checked next schedule and retained scheduler job facts. Defaults to false; not a complete execution history.")
    private Boolean includePolicyContext;

    public boolean isIncludePolicyContext() { return Boolean.TRUE.equals(includePolicyContext); }

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getVolumeId() {
        return volumeId;
    }

    @Override
    public boolean isDisplay() {
        if (display != null) {
            return display;
        }
        return true;
    }

    public Long getId() {
        return id;
    }

    @Override
    public Boolean getDisplay() { return display; }

    public boolean isIgnoreProject() { return Boolean.TRUE.equals(ignoreProject); }

    public String getSortKey() { return sortKey; }

    public String getSortOrder() { return sortOrder; }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() {
        Pair<List<? extends SnapshotPolicy>, Integer> result = _snapshotService.listSnapshotPolicies(this);
        ListResponse<SnapshotPolicyResponse> response = new ListResponse<SnapshotPolicyResponse>();
        List<SnapshotPolicyResponse> policyResponses = new ArrayList<SnapshotPolicyResponse>();
        for (SnapshotPolicy policy : result.first()) {
            SnapshotPolicyResponse policyResponse = _responseGenerator.createSnapshotPolicyResponse(policy);
            if (isIncludePolicyContext()) {
                policyResponse.setPolicyContext(_snapshotService.getSnapshotPolicyContext(policy.getId()));
            }
            policyResponse.setObjectName("snapshotpolicy");
            policyResponses.add(policyResponse);
        }
        response.setResponses(policyResponses, result.second());
        response.setResponseName(getCommandName());
        this.setResponseObject(response);
    }
}

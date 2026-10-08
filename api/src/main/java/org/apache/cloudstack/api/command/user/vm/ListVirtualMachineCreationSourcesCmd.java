/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.api.command.user.vm;

import javax.inject.Inject;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListAccountResourcesCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.ClusterResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.VmCreationSourceResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.vm.VmCreationSourceService;

@APICommand(name = "listVirtualMachineCreationSources", description = "Lists authorized volume and ROOT snapshot deployment sources with eligibility.",
        responseObject = VmCreationSourceResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class ListVirtualMachineCreationSourcesCmd extends BaseListAccountResourcesCmd {
    @Inject protected VmCreationSourceService sourceService;
    @Parameter(name = "sourcekind", type = CommandType.STRING, required = true, description = "volume or snapshot") private String sourcekind;
    @Parameter(name = ApiConstants.ID, type = CommandType.STRING, description = "Source UUID") private String id;
    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class, required = true, description = "Deployment zone") private Long zoneId;
    @Parameter(name = "arch", type = CommandType.STRING, description = "Required architecture") private String arch;
    @Parameter(name = "state", type = CommandType.STRING, description = "Source state") private String state;
    @Parameter(name = "includeunavailable", type = CommandType.BOOLEAN, description = "Include blocked authorized sources (default true)") private Boolean includeUnavailable;
    @Parameter(name = ApiConstants.SERVICE_OFFERING_ID, type = CommandType.UUID, entityType = ServiceOfferingResponse.class, description = "Compute offering for validation") private Long serviceOfferingId;
    @Parameter(name = ApiConstants.CLUSTER_ID, type = CommandType.UUID, entityType = ClusterResponse.class, description = "Required cluster") private Long clusterId;
    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class, description = "Required host") private Long hostId;
    @Parameter(name = "rootstorageid", type = CommandType.UUID, entityType = StoragePoolResponse.class, description = "Snapshot restore target") private Long rootStorageId;
    @Parameter(name = "sourcerevision", type = CommandType.STRING, description = "Previously inspected source revision") private String revision;
    public String getSourceKind() {
        if (!"volume".equals(sourcekind) && !"snapshot".equals(sourcekind)) { throw new InvalidParameterValueException("sourcekind must be volume or snapshot"); }
        return sourcekind;
    }
    @Parameter(name = ApiConstants.PROJECT_ID, type = CommandType.UUID, entityType = org.apache.cloudstack.api.response.ProjectResponse.class, description = "Project scope") private Long projectId;
    public Long getProjectId() { return projectId; }
    public String getId() { return id; }
    public Long getZoneId() { return zoneId; }
    public String getArch() { return arch; }
    public String getState() { return state; }
    public boolean includeUnavailable() { return !Boolean.FALSE.equals(includeUnavailable); }
    public Long getServiceOfferingId() { return serviceOfferingId; }
    public Long getClusterId() { return clusterId; }
    public Long getHostId() { return hostId; }
    public Long getRootStorageId() { return rootStorageId; }
    public String getSourceRevision() { return revision; }
    @Override public void execute() {
        org.apache.cloudstack.api.response.ListResponse<VmCreationSourceResponse> response = sourceService.list(this);
        response.setResponseName(getCommandName()); setResponseObject(response);
    }
}

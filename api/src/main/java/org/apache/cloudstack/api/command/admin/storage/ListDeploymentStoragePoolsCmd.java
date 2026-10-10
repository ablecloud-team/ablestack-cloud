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
package org.apache.cloudstack.api.command.admin.storage;

import javax.inject.Inject;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.ClusterResponse;
import org.apache.cloudstack.api.response.DeploymentStoragePoolResponse;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.PodResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.acl.RoleType;
import com.cloud.storage.VmStorageSelectionService;

@APICommand(name = "listDeploymentStoragePools", description = "Lists primary storage candidates using VM deployment allocators.",
        responseObject = DeploymentStoragePoolResponse.class, authorized = {RoleType.Admin}, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class ListDeploymentStoragePoolsCmd extends BaseListCmd {
    @Inject private VmStorageSelectionService selectionService;
    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class, required = true, description = "Deployment zone") private Long zoneId;
    @Parameter(name = ApiConstants.SERVICE_OFFERING_ID, type = CommandType.UUID, entityType = ServiceOfferingResponse.class, required = true, description = "Compute offering") private Long serviceOfferingId;
    @Parameter(name = ApiConstants.TEMPLATE_ID, type = CommandType.UUID, entityType = TemplateResponse.class, description = "Template or installation ISO (exclusive with snapshotid)") private Long templateId;
    @Parameter(name = ApiConstants.DISK_OFFERING_ID, type = CommandType.UUID, entityType = DiskOfferingResponse.class, description = "Effective disk offering; omitted for the compute root offering") private Long diskOfferingId;
    @Parameter(name = ApiConstants.HYPERVISOR, type = CommandType.STRING, description = "Hypervisor for an ISO") private String hypervisor;
    @Parameter(name = ApiConstants.SIZE, type = CommandType.LONG, description = "Requested size in GiB; omit while a custom ISO root size is not entered") private Long size;
    @Parameter(name = "diskcount", type = CommandType.INTEGER, description = "Number of identical disks (default 1)") private Integer diskCount;
    @Parameter(name = "vmcount", type = CommandType.INTEGER, description = "Number of VMs (default 1)") private Integer vmCount;
    @Parameter(name = "rootdisk", type = CommandType.BOOLEAN, description = "Root disk candidates (default true)") private Boolean rootDisk;
    @Parameter(name = "otherstorageid", type = CommandType.UUID, entityType = StoragePoolResponse.class, description = "Already selected pool for the other disk group") private Long otherStorageId;
    @Parameter(name = "otherrequiredbytes", type = CommandType.LONG, description = "Bytes requested in the other selected pool") private Long otherRequiredBytes;
    @Parameter(name = ApiConstants.POD_ID, type = CommandType.UUID, entityType = PodResponse.class, description = "Deployment pod") private Long podId;
    @Parameter(name = ApiConstants.CLUSTER_ID, type = CommandType.UUID, entityType = ClusterResponse.class, description = "Deployment cluster") private Long clusterId;
    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class, description = "Deployment host") private Long hostId;
    @Parameter(name = ApiConstants.MIN_IOPS, type = CommandType.LONG, description = "Per-disk minimum IOPS") private Long minIops;
    @Parameter(name = "otherrequirediops", type = CommandType.LONG, description = "Minimum IOPS requested in the other selected pool") private Long otherRequiredIops;
    public long getOtherRequiredIops() { return otherRequiredIops == null ? 0 : otherRequiredIops; }
    @Parameter(name = ApiConstants.SNAPSHOT_ID, type = CommandType.UUID, entityType = org.apache.cloudstack.api.response.SnapshotResponse.class, description = "ROOT snapshot restore source") private Long snapshotId;
    @Parameter(name = ApiConstants.VOLUME_ID, type = CommandType.UUID, entityType = org.apache.cloudstack.api.response.VolumeResponse.class, description = "Uploaded volume staging source") private Long volumeId;
    public Long getVolumeId() { return volumeId; }
    public Long getSnapshotId() { return snapshotId; }
    public Long getZoneId() { return zoneId; }
    public Long getServiceOfferingId() { return serviceOfferingId; }
    public Long getTemplateId() { return templateId; }
    public Long getDiskOfferingId() { return diskOfferingId; }
    public String getHypervisor() { return hypervisor; }
    public Long getSize() { return size; }
    public int getDiskCount() { return diskCount == null ? 1 : diskCount; }
    public int getVmCount() { return vmCount == null ? 1 : vmCount; }
    public boolean isRootDisk() { return rootDisk == null || rootDisk; }
    public Long getOtherStorageId() { return otherStorageId; }
    public long getOtherRequiredBytes() { return otherRequiredBytes == null ? 0 : otherRequiredBytes; }
    public Long getPodId() { return podId; }
    public Long getClusterId() { return clusterId; }
    public Long getHostId() { return hostId; }
    public Long getMinIops() { return minIops; }
    @Override public void execute() {
        ListResponse<DeploymentStoragePoolResponse> response = new ListResponse<>();
        response.setResponses(selectionService.listPools(this));
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}

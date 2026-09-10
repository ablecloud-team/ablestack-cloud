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
package com.cloud.dr;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.vm.DeployVMVolumeCmdByAdmin;

import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.vm.VmDetailConstants;

public class DrReplicaDeployVMVolumeCmd extends DeployVMVolumeCmdByAdmin {

    private final long entityOwnerId;
    private final String accountName;
    private final Long domainId;
    private final Long zoneId;
    private final Long serviceOfferingId;
    private final String name;
    private final String displayName;
    private final List<Long> networkIds;
    private final Long hostId;
    private final HypervisorType hypervisor;
    private final Long volumeId;
    private final Map<String, String> details;
    private final DrResolvedTargetHardware hardware;

    public DrReplicaDeployVMVolumeCmd(long entityOwnerId, String accountName, Long domainId, Long zoneId,
                                      Long serviceOfferingId, String name, String displayName, List<Long> networkIds,
                                      Long hostId, HypervisorType hypervisor, Long volumeId, Map<String, String> details) {
        this(entityOwnerId, accountName, domainId, zoneId, serviceOfferingId, name, displayName, networkIds,
                hostId, hypervisor, volumeId, details, null);
    }

    public DrReplicaDeployVMVolumeCmd(long entityOwnerId, String accountName, Long domainId, Long zoneId,
                                      Long serviceOfferingId, String name, String displayName, List<Long> networkIds,
                                      Long hostId, HypervisorType hypervisor, Long volumeId, Map<String, String> details,
                                      DrResolvedTargetHardware hardware) {
        this.entityOwnerId = entityOwnerId;
        this.accountName = accountName;
        this.domainId = domainId;
        this.zoneId = zoneId;
        this.serviceOfferingId = serviceOfferingId;
        this.name = name;
        this.displayName = displayName;
        this.networkIds = networkIds;
        this.hostId = hostId;
        this.hypervisor = hypervisor;
        this.volumeId = volumeId;
        this.details = details;
        this.hardware = hardware;
    }

    @Override
    public long getEntityOwnerId() {
        return entityOwnerId;
    }

    @Override
    public String getAccountName() {
        return accountName;
    }

    @Override
    public Long getDomainId() {
        return domainId;
    }

    @Override
    public Long getZoneId() {
        return zoneId;
    }

    @Override
    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public List<Long> getNetworkIds() {
        return networkIds;
    }

    @Override
    public Map<Long, com.cloud.network.Network.IpAddresses> getIpToNetworkMap() {
        if (!"true".equals(details.get("dr.test.nic.disabled"))) {
            return super.getIpToNetworkMap();
        }
        Map<Long, com.cloud.network.Network.IpAddresses> addresses = new HashMap<>();
        for (Long networkId : networkIds) {
            addresses.put(networkId, new com.cloud.network.Network.IpAddresses(null, null, null, false));
        }
        return addresses;
    }

    @Override
    public Long getHostId() {
        return hostId;
    }

    @Override
    public HypervisorType getHypervisor() {
        return hypervisor;
    }

    @Override
    public Long getVolumeId() {
        return volumeId;
    }

    @Override
    public boolean getStartVm() {
        return false;
    }

    @Override
    public Boolean isDisplayVm() {
        return true;
    }

    @Override
    public boolean isDisplay() {
        return true;
    }

    @Override
    public Map<String, String> getDetails() {
        Map<String, String> merged = new HashMap<String, String>();
        if (details != null) {
            merged.putAll(details);
        }
        if (hardware != null) {
            if (hardware.getBootType() == ApiConstants.BootType.UEFI && hardware.getBootMode() != null) {
                merged.putIfAbsent(ApiConstants.BootType.UEFI.toString(), hardware.getBootMode().toString());
            }
            if (hardware.getIoPolicy() != null) {
                merged.putIfAbsent(VmDetailConstants.IO_POLICY, hardware.getIoPolicy().toString());
            }
            if (Boolean.TRUE.equals(hardware.getIoThreadsEnabled())) {
                merged.putIfAbsent(VmDetailConstants.IOTHREADS, "true");
            }
        }
        return merged;
    }

    @Override
    public ApiConstants.BootType getBootType() {
        return hardware != null ? hardware.getBootType() : null;
    }

    @Override
    public ApiConstants.BootMode getBootMode() {
        return hardware != null ? hardware.getBootMode() : null;
    }

    @Override
    public ApiConstants.IoDriverPolicy getIoDriverPolicy() {
        return hardware != null ? hardware.getIoPolicy() : null;
    }
}

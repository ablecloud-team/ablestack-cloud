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
package com.cloud.vm.process;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import javax.inject.Inject;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.response.VmProcessCapabilityResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.vm.process.VmProcessCapabilityService;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVmProcessCapabilitiesCommand;
import com.cloud.agent.api.GetVmProcessCapabilitiesAnswer;
import com.cloud.agent.api.VmProcessCapability;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.Storage;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VMTemplateZoneVO;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.AccountManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;

public class VmProcessCapabilityServiceImpl extends com.cloud.utils.component.ManagerBase
        implements VmProcessCapabilityService, Configurable, com.cloud.utils.component.PluggableService {
    @Override public java.util.List<Class<?>> getCommands() {
        return java.util.List.of(org.apache.cloudstack.api.command.user.vm.GetVirtualMachineProcessCapabilitiesCmd.class);
    }
    public static final ConfigKey<Boolean> MANAGEMENT_ENABLED = new ConfigKey<>("Advanced", Boolean.class,
            "vm.process.management.enabled", "false", "Explicitly enable VM process observations and management APIs. Disabled by default; no test allowlist bypass.", true);
    public static final ConfigKey<String> TOOLS_ISO_CATALOG = new ConfigKey<>("Advanced", String.class,
            "vm.process.tools.iso.catalog", "[]", "JSON array of registered ABLESTACK Tools ISO UUIDs. ISO family is read from the registered ISO name.", true);
    @Inject private DataCenterDao dataCenterDao;
    @Inject private GuestOSDao guestOSDao;
    @Inject private VMTemplateDao templateDao;
    @Inject private VMTemplateZoneDao templateZoneDao;
    @Inject private UserVmDao vmDao;
    @Inject private AccountManager accountManager;
    @Inject private HostDao hostDao;
    @Inject private AgentManager agentManager;
    private final Semaphore admission = new Semaphore(8);
    @Override public String getConfigComponentName() { return getClass().getSimpleName(); }
    @Override public ConfigKey<?>[] getConfigKeys() { return new ConfigKey<?>[] { MANAGEMENT_ENABLED, TOOLS_ISO_CATALOG }; }
    boolean enabled(UserVmVO vm) {
        return Boolean.TRUE.equals(MANAGEMENT_ENABLED.value());
    }
    @Override public VmProcessCapabilityResponse getCapabilities(long vmId) {
        UserVmVO vm = vmDao.findById(vmId);
        if (vm == null || vm.getRemoved() != null || vm.getType() != VirtualMachine.Type.User)
            throw new InvalidParameterValueException("User VM not found");
        accountManager.checkAccess(CallContext.current().getCallingAccount(), AccessType.ListEntry, true, vm);
        if (!enabled(vm)) throw new InvalidParameterValueException("VM process capability rollout is disabled");
        Long hostId = vm.getHostId(); long generation = vm.getUpdated();
        HostVO host = hostId == null ? null : hostDao.findById(hostId);
        GetVmProcessCapabilitiesCommand command = new GetVmProcessCapabilitiesCommand(vm.getInstanceName(), vm.getUuid(),
                host == null ? "00000000-0000-0000-0000-000000000000" : host.getUuid(), Long.toString(generation), UUID.randomUUID().toString());
        Map<String, Object> result = VmProcessCapability.empty(command);
        if (vm.getHypervisorType() != HypervisorType.KVM) return response(VmProcessCapability.fail(result, "UNSUPPORTED_HYPERVISOR", "Only KVM user VMs are supported"));
        if (vm.getState() != VirtualMachine.State.Running) return response(VmProcessCapability.fail(result, "VM_NOT_RUNNING", "VM must be running"));
        if (host == null || !admission.tryAcquire()) return response(result);
        long started = System.nanoTime();
        try {
            Answer answer = agentManager.send(hostId, command);
            UserVmVO current = vmDao.findById(vmId);
            if (current == null || current.getRemoved() != null || current.getState() != VirtualMachine.State.Running
                    || !Objects.equals(hostId, current.getHostId()) || generation != current.getUpdated()
                    || !vm.getUuid().equals(current.getUuid()) || System.nanoTime() - started > 10_000_000_000L)
                return response(VmProcessCapability.fail(result, "CHECK_FAILED", "VM placement changed or observation expired"));
            accountManager.checkAccess(CallContext.current().getCallingAccount(), AccessType.ListEntry, true, current);
            if (answer instanceof GetVmProcessCapabilitiesAnswer && answer.getResult()) {
                Map<String, Object> observed = ((GetVmProcessCapabilitiesAnswer) answer).getCapability();
                if (observed != null && command.getRequestId().equals(observed.get("requestId"))
                        && result.get("authority").equals(observed.get("authority"))) {
                    result = observed;
                    result.put("observedAt", Instant.now().toString());
                }
            }
        } catch (com.cloud.exception.AgentUnavailableException | com.cloud.exception.OperationTimedoutException e) {
            result = VmProcessCapability.fail(result, "CHECK_FAILED", "Agent observation unavailable");
        } finally { admission.release(); }
        if (!enabled(vm)) throw new InvalidParameterValueException("VM process management is disabled");
        VmProcessCapabilityResponse publicResponse = response(result);
        DataCenterVO zone = dataCenterDao == null ? null : dataCenterDao.findById(vm.getDataCenterId());
        GuestOSVO registeredOs = guestOSDao == null ? null : guestOSDao.findById(vm.getGuestOSId());
        @SuppressWarnings("unchecked") Map<String, Object> os = (Map<String, Object>) result.get("os");
        publicResponse.setToolsIso(VmProcessToolsIsoCatalog.resolve(TOOLS_ISO_CATALOG.value(),
                zone == null ? null : zone.getUuid(), os,
                registeredOs == null ? null : registeredOs.getDisplayName(), isoId -> {
                    VMTemplateVO iso = templateDao == null ? null : templateDao.findByUuid(isoId);
                    if (iso == null) return null;
                    VMTemplateZoneVO zoneRef = zone == null || templateZoneDao == null ? null
                            : templateZoneDao.findByZoneTemplate(zone.getId(), iso.getId());
                    return new VmProcessToolsIsoCatalog.IsoMetadata(iso.getName(),
                            iso.getArch() == null ? null : iso.getArch().getType(), iso.getChecksum(),
                            iso.getFormat() == Storage.ImageFormat.ISO,
                            iso.getRemoved() == null && iso.getState() == VirtualMachineTemplate.State.Active,
                            iso.isBootable(), zone != null && (iso.isCrossZones() || zoneRef != null && zoneRef.getRemoved() == null));
                }));
        return publicResponse;
    }
    static VmProcessCapabilityResponse response(Map<String, Object> internal) {
        Map<String, Object> publicState = new LinkedHashMap<>(internal);
        for (String field : java.util.List.of("qgaVersion", "hostToolsVersion", "guestAdapterVersion"))
            publicState.putIfAbsent(field, null);
        Map<?, ?> authority = (Map<?, ?>) internal.get("authority");
        publicState.put("authority", Map.of("vmUuid", authority.get("vmUuid")));
        VmProcessCapabilityResponse response = new VmProcessCapabilityResponse();
        response.setProcessState(publicState);
        return response;
    }
}

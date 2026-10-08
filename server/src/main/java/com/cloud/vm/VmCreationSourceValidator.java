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
package com.cloud.vm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.inject.Inject;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.api.command.user.vm.ListVirtualMachineCreationSourcesCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.VmCreationSourceResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.springframework.stereotype.Component;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.ServiceOffering;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.AccountManager;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.storage.ScopeType;

/** One eligibility contract used by listing, preflight and real allocation. */
@Component
public class VmCreationSourceValidator extends ManagerBase implements VmCreationSourceService {
    @Inject VolumeDao volumes;
    @Inject SnapshotDao snapshots;
    @Inject VolumeDetailsDao volumeDetails;
    @Inject SnapshotDetailsDao snapshotDetails;
    @Inject VMTemplateDao templates;
    @Inject VMInstanceDao vms;
    @Inject VMInstanceDetailsDao vmDetails;
    @Inject PrimaryDataStoreDao pools;
    @Inject DataCenterDao zones;
    @Inject GuestOSDao guestOs;
    @Inject HostDao hosts;
    @Inject com.cloud.dc.dao.ClusterDao clusters;
    @Inject com.cloud.agent.AgentManager agents;
    @Inject AccountManager accounts;
    @Inject com.cloud.configuration.ConfigurationManager configurationManager;
    @Inject ServiceOfferingDao offerings;
    @Inject com.cloud.storage.dao.DiskOfferingDao diskOfferings;
    @Inject StorageManager storageManager;
    @Inject SnapshotDataFactory snapshotFactory;
    @Inject SnapshotHelper snapshotHelper;
    static final List<String> BOOT_KEYS = Arrays.asList("UEFI", VmDetailConstants.ROOT_DISK_CONTROLLER,
            "kvm.guest.os.machine.type", "video.hardware", "video.ram", VmDetailConstants.TPM_VERSION);

    private <T extends org.apache.cloudstack.acl.ControlledEntity> SearchCriteria<T> authorized(
            SearchBuilder<T> sb, ListVirtualMachineCreationSourcesCmd cmd) {
        List<Long> permitted = new ArrayList<>();
        Ternary<Long, Boolean, ListProjectResourcesCriteria> scope = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accounts.buildACLSearchParameters(CallContext.current().getCallingAccount(), null, cmd.getAccountName(), cmd.getProjectId(),
                permitted, scope, cmd.listAll(), false);
        accounts.buildACLSearchBuilder(sb, scope.first(), scope.second(), permitted, scope.third());
        SearchCriteria<T> sc = sb.create();
        accounts.buildACLSearchCriteria(sc, scope.first(), scope.second(), permitted, scope.third());
        return sc;
    }

    List<VmCreationSourceResponse> sources(ListVirtualMachineCreationSourcesCmd cmd) {
        com.cloud.dc.DataCenter zone = zones.findById(cmd.getZoneId());
        if (zone == null) { throw new InvalidParameterValueException("Deployment zone does not exist"); }
        configurationManager.checkZoneAccess(CallContext.current().getCallingAccount(), zone);
        List<VmCreationSourceResponse> result = new ArrayList<>();
        if ("volume".equals(cmd.getSourceKind())) {
            SearchBuilder<VolumeVO> sb = volumes.createSearchBuilder();
            sb.and("zone", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
            SearchCriteria<VolumeVO> sc = authorized(sb, cmd); sc.setParameters("zone", cmd.getZoneId());
            for (VolumeVO volume : volumes.search(sc, new Filter(VolumeVO.class, "created", false, null, null))) {
                VMInstanceVO vm = volume.getInstanceId() == null ? null : vms.findByIdIncludingRemoved(volume.getInstanceId());
                if (vm != null && vm.getType() != VirtualMachine.Type.User) { continue; }
                result.add(inspect(volume, null, cmd.getZoneId(), cmd.getArch()));
            }
        } else {
            SearchBuilder<SnapshotVO> sb = snapshots.createSearchBuilder();
            sb.and("zone", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
            SearchCriteria<SnapshotVO> sc = authorized(sb, cmd); sc.setParameters("zone", cmd.getZoneId());
            for (SnapshotVO snapshot : snapshots.search(sc, new Filter(SnapshotVO.class, "created", false, null, null))) {
                VolumeVO volume = volumes.findByIdIncludingRemoved(snapshot.getVolumeId());
                result.add(inspect(volume, snapshot, cmd.getZoneId(), cmd.getArch()));
            }
        }
        String keyword = StringUtils.defaultString(cmd.getKeyword()).toLowerCase(Locale.ROOT);
        result.removeIf(source -> cmd.getId() != null && !cmd.getId().equals(source.id)
                || cmd.getState() != null && !cmd.getState().equalsIgnoreCase(source.state)
                || !keyword.isEmpty() && !(source.name + " " + source.id + " " + source.sourcevm.values()).toLowerCase(Locale.ROOT).contains(keyword)
                || !cmd.includeUnavailable() && !source.allowed);
        return result;
    }

    @Override public ListResponse<VmCreationSourceResponse> list(ListVirtualMachineCreationSourcesCmd cmd) {
        List<VmCreationSourceResponse> all = sources(cmd);
        int from = (int) Math.min(all.size(), cmd.getStartIndex() == null ? 0 : cmd.getStartIndex());
        int to = (int) Math.min(all.size(), cmd.getPageSizeVal() == null ? all.size() : (long) from + cmd.getPageSizeVal());
        ListResponse<VmCreationSourceResponse> result = new ListResponse<>();
        result.setResponses(new ArrayList<>(all.subList(from, to)), all.size()); return result;
    }

    @Override public VmCreationSourceResponse validate(ListVirtualMachineCreationSourcesCmd cmd) {
        List<VmCreationSourceResponse> found = sources(cmd);
        if (found.size() != 1) { throw new InvalidParameterValueException("Creation source not found or unavailable to this account"); }
        VmCreationSourceResponse source = found.get(0);
        deploymentConstraints(source, cmd.getServiceOfferingId(), cmd.getClusterId(), cmd.getHostId(), cmd.getRootStorageId(), cmd.getSourceRevision());
        if (source.allowed) { validateRuntime(source); }
        return source;
    }

    VmCreationSourceResponse inspect(VolumeVO volume, SnapshotVO snapshot, Long zoneId, String arch) {
        VmCreationSourceResponse out = new VmCreationSourceResponse(); out.allowed = true;
        out.sourcekind = snapshot == null ? "volume" : "snapshot";
        out.sourceusage = snapshot == null ? "adopt-existing" : "restore-new";
        out.id = snapshot == null ? volume.getUuid() : snapshot.getUuid();
        out.name = snapshot == null ? volume.getName() : snapshot.getName();
        out.state = snapshot == null ? volume.getState().name() : snapshot.getState().name();
        out.snapshotcreated = snapshot == null ? null : snapshot.getCreated();
        long sourceZone = snapshot == null ? volume.getDataCenterId() : snapshot.getDataCenterId();
        out.zoneid = zones.findById(sourceZone).getUuid();
        if (!Objects.equals(zoneId, sourceZone)) { out.reject("SOURCE_ZONE_MISMATCH"); }
        Map<String, String> saved = snapshot == null ? metadata(volumeDetails.listDetailsKeyPairs(volume.getId()))
                : metadata(snapshotDetails.listDetailsKeyPairs(snapshot.getId()));
        if (saved.isEmpty() && volume != null) {
            // Legacy snapshots never inherit the CURRENT VM's mutable boot settings.
            saved = profile(volume, snapshot == null);
            saved.put("provenance", snapshot == null ? "volume" : "legacy-template");
        }
        out.bootprofile.putAll(saved);
        out.volumetype = saved.getOrDefault("volumetype", volume == null ? "UNKNOWN" : volume.getVolumeType().name());
        if (!"ROOT".equals(out.volumetype)) { out.reject("ROOT_PROVENANCE_UNKNOWN"); }
        out.sourcevolumeid = saved.getOrDefault("sourcevolumeid", volume == null ? null : volume.getUuid());
        out.sizebytes = longValue(saved.get("sizebytes"), snapshot == null ? volume.getSize() : snapshot.getSize());
        Long templateId = longValue(saved.get("templateid"), volume == null ? null : volume.getTemplateId());
        VMTemplateVO template = templateId == null ? null : templates.findByIdIncludingRemoved(templateId);
        if (template == null) { out.reject("SOURCE_TEMPLATE_MISSING"); }
        else {
            out.templateid = template.getUuid();
            out.arch = saved.getOrDefault("arch", template.getArch() == null ? "x86_64" : template.getArch().toString());
            out.hypervisor = snapshot == null ? template.getHypervisorType().name() : snapshot.getHypervisorType().name();
            if (!HypervisorType.KVM.name().equals(out.hypervisor)) { out.reject("HYPERVISOR_UNSUPPORTED"); }
            if (arch != null && !arch.equalsIgnoreCase(out.arch)) { out.reject("ARCH_MISMATCH"); }
        }
        if (out.sizebytes == null || out.sizebytes <= 0 || !saved.containsKey("ostypeid") || !saved.containsKey("boottype")) {
            out.reject("BOOT_PROFILE_INCOMPLETE");
        }
        if (!"NONE".equalsIgnoreCase(saved.getOrDefault(VmDetailConstants.TPM_VERSION, "NONE"))) { out.reject("TPM_STATE_UNSUPPORTED"); }
        if ("SECURE".equalsIgnoreCase(saved.get("bootmode"))) { out.reject("SECURE_BOOT_STATE_UNSUPPORTED"); }
        if (snapshot == null) {
            if (volume.getInstanceId() != null) { out.reject("SOURCE_ATTACHED"); }
            if (volume.getState() != Volume.State.Ready || StringUtils.isBlank(volume.getPath())) { out.reject("SOURCE_NOT_READY"); }
        } else if (!restorable(snapshot)) { out.reject("SNAPSHOT_NOT_RESTORABLE"); }
        StoragePoolVO pool = volume == null || volume.getPoolId() == null ? null : pools.findByIdIncludingRemoved(volume.getPoolId());
        if (snapshot == null && (pool == null || pool.getStatus() != StoragePoolStatus.Up || pool.getRemoved() != null)) { out.reject("STORAGE_UNAVAILABLE"); }
        if (snapshot == null && pool != null && !supportedPool(pool)) { out.reject("STORAGE_SCOPE_UNSUPPORTED"); }
        if (pool != null && accounts.isRootAdmin(CallContext.current().getCallingAccount().getId())) {
            out.storage.put("id", pool.getUuid()); out.storage.put("name", pool.getName());
            out.storage.put("type", pool.getPoolType().name()); out.storage.put("scope", pool.getScope().name());
            if (pool.getClusterId() != null && clusters.findById(pool.getClusterId()) != null) {
                out.storage.put("clusterid", clusters.findById(pool.getClusterId()).getUuid());
                out.storage.put("clustername", clusters.findById(pool.getClusterId()).getName());
            }
        }
        Long sourceVmId = longValue(saved.get("sourcevmid"), null);
        VMInstanceVO sourceVm = sourceVmId == null ? null : vms.findByIdIncludingRemoved(sourceVmId);
        if (sourceVm != null) {
            try { accounts.checkAccess(CallContext.current().getCallingAccount(), null, true, sourceVm);
                out.sourcevm.put("id", sourceVm.getUuid()); out.sourcevm.put("name", sourceVm.getHostName());
                out.sourcevm.put("displayname", sourceVm.getHostName());
            } catch (PermissionDeniedException denied) { /* Do not disclose a source outside caller visibility. */ }
        }
        out.bootprofile.remove("sourcevmid"); out.bootprofile.remove("templateid");
        out.revision = DigestUtils.sha256Hex((out.id + ":" + out.state + ":" + (volume == null ? "missing" : volume.getInstanceId() + ":" + volume.getPoolId())
                + ":" + new TreeMap<>(saved) + ":" + out.reasoncodes).getBytes(StandardCharsets.UTF_8));
        return out;
    }

    static Long longValue(String value, Long fallback) {
        try { return value == null ? fallback : Long.valueOf(value); } catch (NumberFormatException invalid) { return null; }
    }
    static boolean supportedPool(StoragePoolVO pool) {
        return pool.getScope() == ScopeType.ZONE || pool.getScope() == ScopeType.CLUSTER
                && (pool.getPoolType() == Storage.StoragePoolType.SharedMountPoint || pool.getPoolType() == Storage.StoragePoolType.RBD);
    }
    boolean restorable(SnapshotVO snapshot) {
        if (snapshot.getState() != Snapshot.State.BackedUp && snapshot.getState() != Snapshot.State.CreatedOnPrimary) { return false; }
        try {
            SnapshotInfo info = snapshotFactory.getSnapshotWithRoleAndZone(snapshot.getId(), snapshotHelper.getDataStoreRole(snapshot), snapshot.getDataCenterId());
            return info != null && info.getDataStore() != null && StringUtils.isNotBlank(info.getPath())
                    && info.getStatus() == org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.State.Ready;
        } catch (RuntimeException unavailable) { return false; }
    }

    void deploymentConstraints(VmCreationSourceResponse source, Long offeringId, Long clusterId, Long hostId, Long rootStorageId, String revision) {
        if (revision != null && !revision.equals(source.revision)) { source.reject("SOURCE_CHANGED"); }
        VolumeVO volume = "volume".equals(source.sourcekind) ? volumes.findByUuid(source.id) : null;
        StoragePoolVO sourcePool = volume == null || volume.getPoolId() == null ? null : pools.findById(volume.getPoolId());
        HostVO host = hostId == null ? null : hosts.findById(hostId);
        if (hostId != null && (host == null || host.getHypervisorType() != HypervisorType.KVM || host.getStatus() != com.cloud.host.Status.Up
                || !zones.findById(host.getDataCenterId()).getUuid().equals(source.zoneid))) { source.reject("HOST_UNAVAILABLE"); }
        if (sourcePool != null) {
            if (clusterId != null && sourcePool.getScope() == ScopeType.CLUSTER && !clusterId.equals(sourcePool.getClusterId())
                    || host != null && sourcePool.getScope() == ScopeType.CLUSTER && !Objects.equals(host.getClusterId(), sourcePool.getClusterId())) {
                source.reject("SOURCE_CLUSTER_MISMATCH");
            }
            if (rootStorageId != null && !rootStorageId.equals(sourcePool.getId())) { source.reject("SOURCE_STORAGE_FIXED"); }
        }
        if (offeringId != null) {
            ServiceOffering offering = offerings.findById(offeringId);
            com.cloud.offering.DiskOffering disk = offering == null ? null : diskOfferings.findById(offering.getDiskOfferingId());
            if (offering == null || disk == null || disk.isUseLocalStorage()) { source.reject("OFFERING_INCOMPATIBLE"); }
            if (volume != null && offering != null && Boolean.TRUE.equals(offering.getDiskOfferingStrictness())
                    && !Objects.equals(offering.getDiskOfferingId(), volume.getDiskOfferingId())) { source.reject("OFFERING_INCOMPATIBLE"); }
        }
        if (rootStorageId != null && volume == null) {
            if (!accounts.isRootAdmin(CallContext.current().getCallingAccount().getId())) { throw new PermissionDeniedException("Direct storage selection requires administrator permission"); }
            StoragePoolVO target = pools.findById(rootStorageId);
            if (target == null || target.getStatus() != StoragePoolStatus.Up || !zones.findById(target.getDataCenterId()).getUuid().equals(source.zoneid)) { source.reject("STORAGE_UNAVAILABLE"); }
            else if (!storageManager.storagePoolHasEnoughSpace(source.sizebytes, target)) { source.reject("CAPACITY_UNAVAILABLE"); }
        }
    }

    @Override public VmCreationSourceResponse validateDeployment(DeployVMCmd cmd) {
        VolumeVO volume;
        SnapshotVO snapshot = null;
        if (cmd.getVolumeId() != null) { volume = volumes.findById(cmd.getVolumeId()); }
        else { snapshot = snapshots.findById(cmd.getSnapshotId());
            if (snapshot == null) { throw new InvalidParameterValueException("Creation snapshot does not exist"); }
            accounts.checkAccess(CallContext.current().getCallingAccount(), null, true, snapshot);
            volume = volumes.findByIdIncludingRemoved(snapshot.getVolumeId());
        }
        if (snapshot == null && volume == null) { throw new InvalidParameterValueException("Creation volume does not exist"); }
        if (snapshot == null) { accounts.checkAccess(CallContext.current().getCallingAccount(), null, true, volume); }
        long sourceOwner = snapshot == null ? volume.getAccountId() : snapshot.getAccountId();
        if (sourceOwner != cmd.getEntityOwnerId()) { throw new InvalidParameterValueException("SOURCE_OWNER_MISMATCH: source and VM must have the same owner"); }
        VmCreationSourceResponse source = inspect(volume, snapshot, cmd.getZoneId(), null);
        deploymentConstraints(source, cmd.getServiceOfferingId(), cmd.getSourceClusterId(), cmd.getHostId(), cmd.getRootStorageId(), cmd.getSourceRevision());
        if (source.allowed) { validateRuntime(source); }
        if (!source.allowed) { throw new InvalidParameterValueException("Creation source unavailable: " + String.join(",", source.reasoncodes)); }
        if (cmd.getBootType() != null && !cmd.getBootType().name().equals(source.bootprofile.get("boottype"))) {
            throw new InvalidParameterValueException("BOOT_PROFILE_MISMATCH: boot type must match the source");
        }
        if (cmd.getBootType() == ApiConstants.BootType.UEFI && !cmd.getBootMode().name().equals(source.bootprofile.get("bootmode"))) {
            throw new InvalidParameterValueException("BOOT_PROFILE_MISMATCH: boot mode must match the source");
        }
        if (!cmd.getAdditionalIsoIds().isEmpty() || cmd.getUserData() != null || cmd.getUserdataId() != null
                || cmd.getSSHKeyPairNames() != null && !cmd.getSSHKeyPairNames().isEmpty() || cmd.getRootDiskKmsKeyId() != null || cmd.getDetails().containsKey(VmDetailConstants.ROOT_DISK_SIZE)
                || cmd.getOverrideDiskOfferingId() != null || StringUtils.isNotBlank(cmd.getExtraConfig())) {
            throw new InvalidParameterValueException("SOURCE_CUSTOMIZATION_UNSUPPORTED: existing guest identity and disk encryption are preserved");
        }
        source.bootprofile.put("sourceid", source.id); source.bootprofile.put("sourcekind", source.sourcekind);
        cmd.setSourceBootProfile(source.bootprofile);
        return source;
    }

    void validateRuntime(VmCreationSourceResponse source) {
        if (!"volume".equals(source.sourcekind)) { return; }
        VolumeVO volume = volumes.findByUuid(source.id);
        StoragePoolVO pool = pools.findById(volume.getPoolId());
        int checked = 0;
        for (HostVO host : hosts.listAllRoutingHostsByZoneAndHypervisorType(volume.getDataCenterId(), HypervisorType.KVM)) {
            if (pool.getScope() == ScopeType.CLUSTER && !Objects.equals(pool.getClusterId(), host.getClusterId())) { continue; }
            if (host.getStatus() != com.cloud.host.Status.Up) { source.reject("SOURCE_RUNTIME_UNKNOWN"); return; }
            com.cloud.agent.api.Answer answer = agents.easySend(host.getId(), new com.cloud.agent.api.CheckVolumeUseCommand(volume.getPath()));
            if (answer == null || !answer.getResult()) {
                source.reject(answer != null && "SOURCE_IN_USE".equals(answer.getDetails()) ? "SOURCE_IN_USE" : "SOURCE_RUNTIME_UNKNOWN"); return;
            }
            checked++;
        }
        if (checked == 0) { source.reject("SOURCE_RUNTIME_UNKNOWN"); }
    }

    static Map<String, String> metadata(Map<String, String> details) {
        Map<String, String> result = new LinkedHashMap<>();
        details.forEach((key, value) -> { if (key.startsWith(PREFIX)) { result.put(key.substring(PREFIX.length()), value); } });
        return result;
    }
    Map<String, String> profile(Volume volume, boolean captureVm) {
        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("volumetype", volume.getVolumeType().name()); profile.put("sizebytes", String.valueOf(volume.getSize()));
        profile.put("sourcevolumeid", volume.getUuid());
        VMTemplateVO template = volume.getTemplateId() == null ? null : templates.findByIdIncludingRemoved(volume.getTemplateId());
        if (template == null) { return profile; }
        templates.loadDetails(template);
        profile.put("templateid", String.valueOf(template.getId()));
        profile.put("arch", template.getArch() == null ? "x86_64" : template.getArch().toString());
        Map<String, String> boot = new LinkedHashMap<>(template.getDetails() == null ? Collections.emptyMap() : template.getDetails());
        long osId = template.getGuestOSId();
        VMInstanceVO vm = captureVm && volume.getInstanceId() != null ? vms.findByIdIncludingRemoved(volume.getInstanceId()) : null;
        if (vm != null) {
            boot = vmDetails.listDetailsKeyPairs(vm.getId()); osId = vm.getGuestOSId();
            profile.put("sourcevmid", String.valueOf(vm.getId()));
        }
        for (String key : BOOT_KEYS) { if (boot.containsKey(key)) { profile.put(key, boot.get(key)); } }
        GuestOSVO os = guestOs.findById(osId);
        if (os != null) { profile.put("ostypeid", os.getUuid()); profile.put("osname", os.getDisplayName()); }
        profile.put("boottype", boot.containsKey("UEFI") ? "UEFI" : "BIOS");
        profile.put("bootmode", boot.getOrDefault("UEFI", "LEGACY"));
        profile.put("rootbus", boot.getOrDefault(VmDetailConstants.ROOT_DISK_CONTROLLER, "os-default"));
        profile.put("provenance", "captured"); return profile;
    }
    @Override public void captureSnapshot(Snapshot snapshot, Volume volume) {
        Map<String, String> profile = profile(volume, true);
        profile.forEach((key, value) -> snapshotDetails.addDetail(snapshot.getId(), PREFIX + key, value, false));
    }
    @Override public void captureRestoredVolume(Snapshot snapshot, Volume volume) {
        Map<String, String> saved = metadata(snapshotDetails.listDetailsKeyPairs(snapshot.getId()));
        if (saved.isEmpty()) {
            VolumeVO original = volumes.findByIdIncludingRemoved(snapshot.getVolumeId());
            if (original == null) { return; }
            saved = profile(original, false); saved.put("provenance", "legacy-template");
        }
        if (!"ROOT".equals(saved.get("volumetype"))) { return; }
        saved.forEach((key, value) -> volumeDetails.addDetail(volume.getId(), PREFIX + key, value, false));
    }
    @Override public void captureVolume(Volume volume) {
        if (volume.getVolumeType() != Volume.Type.ROOT) { return; }
        profile(volume, true).forEach((key, value) -> volumeDetails.addDetail(volume.getId(), PREFIX + key, value, false));
    }
}

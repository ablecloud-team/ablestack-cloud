// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.SharedFSService;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;

public class StorageConfigCloneAllocationProvenanceTest {
    private static String uuid(int value) { return String.format("00000000-0000-4000-8000-%012d", value); }
    private final class Manager extends StorageServiceManagerImpl {
        @Override protected void requireConfigurationAdministrator() { }
        @Override protected CreateSharedFSCmd configurationCreateCommand(JsonObject blueprint) { return command; }
        @Override protected void requireConfigurationCloneTemplatePin(JsonObject blueprint, JsonObject pin, StorageServiceInstanceVO created) {
            if (denyNewPermit) throw new InvalidParameterValueException("Controlled new-allocation permit rejected");
        }
        @Override protected StorageServiceInstanceVO requireInstance(Long id) { return source; }
    }
    private Manager manager;
    private CreateSharedFSCmd command;
    private SharedFSService service;
    private SharedFSVO shared;
    private StorageServiceInstanceVO source;
    private StorageServiceInstanceVO target;
    private StorageConfigArtifactDao artifacts;
    private AtomicReference<String> metadata;
    private AtomicReference<Long> vmId;
    private AtomicReference<Long> dataId;
    private boolean sharedPresent;
    private boolean denyPublication;
    private boolean denyVmPublication;
    private boolean denyNewPermit;
    private boolean failAfterVmCommit;
    private boolean failBeforeVm;
    private boolean failDuringVmAllocation;
    private boolean preservePartialReady;
    private boolean failStartWithPartialRootReady;
    private int allocations;
    private int deployments;
    private int vmAllocations;
    private int starts;
    private List<String> events;
    private String snapshotMetadata;
    private boolean snapshotShared;
    private Long snapshotVm;
    private Long snapshotData;
    private boolean transactionSnapshot;
    private TransactionLegacy connectionScope;
    private JsonObject plan;
    private VolumeVO rootDisk;
    private VolumeVO dataDisk;
    private org.apache.cloudstack.storage.datastore.db.StoragePoolVO formatPool;
    private NicVO allocationNic;
    private com.cloud.network.dao.NetworkVO allocationNetwork;

    private StorageConfigArtifactVO artifact() {
        StorageConfigArtifactVO result = new StorageConfigArtifactVO();
        ReflectionTestUtils.setField(result, "id", 900L);ReflectionTestUtils.setField(result, "uuid", uuid(900));
        ReflectionTestUtils.setField(result, "instanceId", 1L);ReflectionTestUtils.setField(result, "sha256", "a".repeat(64));
        result.setMetadataJson(metadata.get());return result;
    }
    private void snapshot() {
        if (transactionSnapshot) return;
        snapshotMetadata = metadata.get();snapshotShared = sharedPresent;snapshotVm = vmId.get();snapshotData = dataId.get();
        transactionSnapshot = true;
    }
    private void rollback() {
        if (transactionSnapshot) {
            metadata.set(snapshotMetadata);sharedPresent = snapshotShared;vmId.set(snapshotVm);dataId.set(snapshotData);
            transactionSnapshot = false;
        }
        events.add("ROLLBACK");
    }

    @Before public void setup() throws Exception {
        events = new ArrayList<>();metadata = new AtomicReference<>();vmId = new AtomicReference<>();dataId = new AtomicReference<>();
        JsonObject initial = new JsonObject();initial.addProperty("allocationNamespace", uuid(910));initial.addProperty("plannedTargetInstanceUuid", uuid(911));
        metadata.set(initial.toString());
        command = Mockito.mock(CreateSharedFSCmd.class);Mockito.when(command.getNetworkId()).thenReturn(44L);Mockito.when(command.getStorageId()).thenReturn(80L);
        manager = new Manager();service = Mockito.mock(SharedFSService.class);
        shared = Mockito.mock(SharedFSVO.class);
        Mockito.when(shared.getId()).thenReturn(100L);Mockito.when(shared.getUuid()).thenReturn(uuid(100));
        Mockito.when(shared.getAccountId()).thenReturn(2L);Mockito.when(shared.getDomainId()).thenReturn(1L);
        Mockito.when(shared.getDataCenterId()).thenReturn(1L);Mockito.when(shared.getServiceOfferingId()).thenReturn(300L);
        Mockito.when(shared.getName()).thenReturn("public-clone");Mockito.when(shared.getFsProviderName()).thenReturn("STORAGE_VM");
        Mockito.when(shared.getFsType()).thenReturn(SharedFS.FileSystemType.XFS);
        Mockito.when(shared.getBackingVolumeMode()).thenReturn(SharedFS.BackingVolumeMode.NEW);
        Mockito.when(shared.getVmId()).thenAnswer(call -> vmId.get());Mockito.when(shared.getVolumeId()).thenAnswer(call -> dataId.get());
        source = Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(source.getUuid()).thenReturn(uuid(1));
        target = Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(target.getUuid()).thenReturn(uuid(20));
        artifacts = Mockito.mock(StorageConfigArtifactDao.class);
        Mockito.when(artifacts.lockRow(900L, true)).thenAnswer(call -> artifact());
        Mockito.when(artifacts.findById(900L)).thenAnswer(call -> artifact());
        Mockito.when(artifacts.update(Mockito.eq(900L), Mockito.any())).thenAnswer(call -> {
            if (denyPublication || denyVmPublication && ((StorageConfigArtifactVO) call.getArgument(1)).getMetadataJson().contains("\"vm\"")) return false;
            StorageConfigArtifactVO row = call.getArgument(1);metadata.set(row.getMetadataJson());events.add("RECORD");return true;
        });
        Mockito.when(service.allocSharedFS(command)).thenAnswer(call -> { allocations++;sharedPresent = true;events.add("ALLOC_SHARED");return shared; });
        Mockito.when(service.getSharedFSByUuid(uuid(100))).thenAnswer(call -> sharedPresent ? shared : null);
        Mockito.when(service.preflightSharedFS(command, 100L)).thenReturn(shared);
        Mockito.when(service.deploySharedFS(Mockito.eq(command), Mockito.any())).thenAnswer(call -> {
            deployments++;if (failBeforeVm) throw new CloudRuntimeException("Controlled deployment entry failure");
            java.util.function.Consumer<SharedFS> recorder = call.getArgument(1);
            if (vmId.get() == null) {
                try (TransactionLegacy transaction = TransactionLegacy.open("ControlledProviderAllocation")) {
                    transaction.start();vmAllocations++;vmId.set(7L);dataId.set(502L);events.add("ALLOC_VM");
                    if (failDuringVmAllocation) throw new CloudRuntimeException("Controlled partial DB allocation failure");
                    recorder.accept(shared);transaction.commit();
                }
            } else recorder.accept(shared);
            starts++;events.add("START");
            if (failStartWithPartialRootReady) {
                Mockito.when(rootDisk.getState()).thenReturn(Volume.State.Ready);Mockito.when(rootDisk.getPoolId()).thenReturn(80L);
                Mockito.when(dataDisk.getState()).thenReturn(Volume.State.Creating);
                throw new CloudRuntimeException("Controlled partial start failure");
            }
            if (failAfterVmCommit) throw new CloudRuntimeException("Controlled start response loss");
            if (!preservePartialReady) {
                Mockito.when(rootDisk.getState()).thenReturn(Volume.State.Ready);Mockito.when(dataDisk.getState()).thenReturn(Volume.State.Ready);
                Mockito.when(rootDisk.getPoolId()).thenReturn(80L);Mockito.when(dataDisk.getPoolId()).thenReturn(80L);
            }
            return shared;
        });
        SharedFSDao sharedDao = Mockito.mock(SharedFSDao.class);Mockito.when(sharedDao.findById(100L)).thenReturn(shared);
        StorageServiceInstanceDao instances = Mockito.mock(StorageServiceInstanceDao.class);Mockito.when(instances.findByVmId(7L)).thenReturn(target);
        UserVmDao vms = Mockito.mock(UserVmDao.class);UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getId()).thenReturn(7L);Mockito.when(vm.getUuid()).thenReturn(uuid(7));
        Mockito.when(vm.getAccountId()).thenReturn(2L);Mockito.when(vm.getDataCenterId()).thenReturn(1L);
        Mockito.when(vm.getTemplateId()).thenReturn(600L);Mockito.when(vm.getUserVmType()).thenReturn(UserVmManager.SHAREDFSVM);
        Mockito.when(vms.findById(7L)).thenReturn(vm);
        com.cloud.storage.dao.VolumeDao volumes = Mockito.mock(com.cloud.storage.dao.VolumeDao.class);
        VolumeVO root = disk(501, Volume.Type.ROOT, 400, 16L << 30, 600L);rootDisk = root;
        VolumeVO data = disk(502, Volume.Type.DATADISK, 401, 20L << 30, null);dataDisk = data;
        Mockito.when(volumes.findByInstanceAndType(7L, Volume.Type.ROOT)).thenReturn(List.of(root));
        Mockito.when(volumes.findByInstanceAndType(7L, Volume.Type.DATADISK)).thenReturn(List.of(data));
        NicDao nics = Mockito.mock(NicDao.class);NicVO nic = Mockito.mock(NicVO.class);allocationNic = nic;
        Mockito.when(nic.getMacAddress()).thenReturn("02:00:00:00:00:99");Mockito.when(nic.getIPv4Address()).thenReturn("10.1.0.9");
        Mockito.when(nic.getMode()).thenReturn(com.cloud.network.Networks.Mode.Dhcp);Mockito.when(nic.isDefaultNic()).thenReturn(true);
        Mockito.when(nic.getAddressFormat()).thenReturn(com.cloud.network.Networks.AddressFormat.Ip4);
        com.cloud.network.dao.NetworkDao networks = Mockito.mock(com.cloud.network.dao.NetworkDao.class);
        allocationNetwork = Mockito.mock(com.cloud.network.dao.NetworkVO.class);Mockito.when(networks.findById(44L)).thenReturn(allocationNetwork);
        Mockito.when(allocationNetwork.getGuruName()).thenReturn("ExternalGuestNetworkGuru");
        Mockito.when(allocationNetwork.getCidr()).thenReturn("10.1.0.0/24");Mockito.when(allocationNetwork.getGateway()).thenReturn("10.1.0.1");
        ReflectionTestUtils.setField(manager, "networkDao", networks);
        Mockito.when(nic.getId()).thenReturn(99L);Mockito.when(nic.getUuid()).thenReturn(uuid(99));Mockito.when(nic.getNetworkId()).thenReturn(44L);
        Mockito.when(nics.listByVmId(7L)).thenReturn(List.of(nic));
        VMTemplateDao templates = Mockito.mock(VMTemplateDao.class);VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.getId()).thenReturn(600L);Mockito.when(template.getUuid()).thenReturn(uuid(600));
        Mockito.when(template.getTemplateType()).thenReturn(Storage.TemplateType.SYSTEM);
        Mockito.when(template.getState()).thenReturn(com.cloud.template.VirtualMachineTemplate.State.Active);
        Mockito.when(template.isDynamicallyScalable()).thenReturn(true);Mockito.when(template.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        Mockito.when(template.getChecksum()).thenReturn("{SHA-256}" + "b".repeat(64));
        Mockito.when(template.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);
        Mockito.when(template.getArch()).thenReturn(com.cloud.cpu.CPU.CPUArch.amd64);
        Mockito.when(template.getDetails()).thenReturn(Map.of());
        Mockito.when(templates.findByUuid(uuid(600))).thenReturn(template);
        DataCenterDao zones = Mockito.mock(DataCenterDao.class);DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(1L);Mockito.when(zones.findByUuid(uuid(30))).thenReturn(zone);
        for (Map.Entry<String, Object> entry : Map.of("configurationSharedFsService", service, "storageConfigArtifactDao", artifacts,
                "sharedFSDao", sharedDao, "storageServiceInstanceDao", instances, "rootUpgradeVmDao", vms,
                "volumeDao", volumes, "nicDao", nics, "rootUpgradeTemplateDao", templates, "dataCenterDao", zones).entrySet())
            ReflectionTestUtils.setField(manager, entry.getKey(), entry.getValue());
        plan = new JsonObject();plan.addProperty("targetMode", "CREATE_NEW");plan.addProperty("artifactSha256", "a".repeat(64));
        JsonObject blueprint = new JsonObject();blueprint.addProperty("templateid", uuid(600));blueprint.addProperty("zoneid", uuid(30));
        plan.add("createNew", blueprint);plan.add("cloneTemplatePin", (JsonObject) ReflectionTestUtils.invokeMethod(manager, "configurationCloneTemplatePin", blueprint, template, "EXPLICIT_SYSTEM"));
        plan.add("cloneRuntimePin", new JsonObject());plan.add("volumeAllocationPlan", new JsonObject());plan.addProperty("runtimeBundleUuid", uuid(700));
        org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao pools = Mockito.mock(org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao.class);
        formatPool = Mockito.mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class);
        Mockito.when(formatPool.getId()).thenReturn(80L);Mockito.when(formatPool.getUuid()).thenReturn(uuid(80));
        Mockito.when(formatPool.isShared()).thenReturn(true);Mockito.when(formatPool.getDataCenterId()).thenReturn(1L);Mockito.when(formatPool.getStatus()).thenReturn(com.cloud.storage.StoragePoolStatus.Up);
        Mockito.when(formatPool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);Mockito.when(pools.findById(80L)).thenReturn(formatPool);
        ReflectionTestUtils.setField(manager, "configurationStoragePoolDao", pools);
        com.cloud.storage.dao.DiskOfferingDao offerings = Mockito.mock(com.cloud.storage.dao.DiskOfferingDao.class);
        for (long offeringId : List.of(400L, 401L)) {
            com.cloud.storage.DiskOfferingVO offering = Mockito.mock(com.cloud.storage.DiskOfferingVO.class);
            Mockito.when(offerings.findById(offeringId)).thenReturn(offering);
        }
        ReflectionTestUtils.setField(manager, "configurationDiskOfferingDao", offerings);
        com.cloud.storage.VolumeApiService api = Mockito.mock(com.cloud.storage.VolumeApiService.class);
        Mockito.when(api.doesStoragePoolSupportDiskOffering(Mockito.any(), Mockito.any())).thenReturn(true);
        ReflectionTestUtils.setField(manager, "volumeApiService", api);
        Connection connection = Mockito.mock(Connection.class);Mockito.when(connection.isValid(Mockito.anyInt())).thenReturn(true);
        Mockito.doAnswer(call -> { snapshot();return null; }).when(connection).setAutoCommit(false);
        Mockito.doAnswer(call -> { events.add("COMMIT");transactionSnapshot = false;return null; }).when(connection).commit();
        Mockito.doAnswer(call -> { rollback();return null; }).when(connection).rollback();
        connectionScope = TransactionLegacy.open("CloneAllocationTestConnection");connectionScope.transitToUserManagedConnection(connection);
    }
    private VolumeVO disk(long id, Volume.Type type, long offering, long size, Long templateId) {
        VolumeVO disk = Mockito.mock(VolumeVO.class);
        Mockito.when(disk.getId()).thenReturn(id);Mockito.when(disk.getUuid()).thenReturn(uuid((int) id));Mockito.when(disk.getPath()).thenReturn("public-volume-" + id);
        Mockito.when(disk.getAccountId()).thenReturn(2L);Mockito.when(disk.getDataCenterId()).thenReturn(1L);
        Mockito.when(disk.getInstanceId()).thenReturn(7L);Mockito.when(disk.getVolumeType()).thenReturn(type);
        Mockito.when(disk.getDiskOfferingId()).thenReturn(offering);Mockito.when(disk.getSize()).thenReturn(size);
        Mockito.when(disk.getState()).thenReturn(Volume.State.Allocated);Mockito.when(disk.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        Mockito.when(disk.getTemplateId()).thenReturn(templateId);Mockito.when(disk.getProvisioningType()).thenReturn(Storage.ProvisioningType.SPARSE);
        return disk;
    }
    @After public void close() {
        if (connectionScope != null) { ReflectionTestUtils.setField(connectionScope, "_conn", null);connectionScope.close(); }
    }
    private StorageServiceInstanceVO create() { return manager.createConfigurationNewService(plan.getAsJsonObject("createNew"), plan, artifact()); }
    private JsonObject receipt() { return JsonParser.parseString(metadata.get()).getAsJsonObject().getAsJsonObject("cloneAllocation"); }

    @Test public void failedArtifactPublicationRollsBackSharedFsAndNeverDeploys() {
        denyPublication = true;
        Assert.assertThrows(RuntimeException.class, this::create);
        Assert.assertFalse(sharedPresent);Assert.assertFalse(JsonParser.parseString(metadata.get()).getAsJsonObject().has("cloneAllocation"));
        Assert.assertEquals(1, allocations);Assert.assertEquals(0, deployments);Assert.assertTrue(events.contains("ROLLBACK"));
    }
    @Test public void committedVmAndDisksAreReusedAfterStartResponseLoss() {
        failAfterVmCommit = true;Assert.assertThrows(RuntimeException.class, this::create);
        JsonObject original = receipt().deepCopy();Assert.assertEquals(7, original.getAsJsonObject("vm").get("vmId").getAsLong());
        Assert.assertTrue(events.lastIndexOf("COMMIT") < events.indexOf("START"));
        failAfterVmCommit = false;Assert.assertSame(target, create());
        Assert.assertEquals(1, allocations);Assert.assertEquals(1, vmAllocations);Assert.assertEquals(original, receipt());
    }
    @Test public void sharedFsCommitReturnLossNeverAllocatesAnotherSharedFs() {
        failBeforeVm = true;Assert.assertThrows(RuntimeException.class, this::create);
        Assert.assertTrue(sharedPresent);Assert.assertFalse(receipt().has("vm"));
        failBeforeVm = false;Assert.assertSame(target, create());Assert.assertEquals(1, allocations);Assert.assertEquals(1, vmAllocations);
    }
    @Test public void retainedAllocationRejectsChangedSourceOwnerRootDataAndUnknownFieldsBeforeDeploy() {
        failAfterVmCommit = true;Assert.assertThrows(RuntimeException.class, this::create);failAfterVmCommit = false;
        String originalMetadata = metadata.get();int originalDeployments = deployments;
        for (String path : List.of("scope.sourceInstanceUuid", "sharedFs.accountId", "vm.root.uuid", "vm.data.id", "vm.nicUuid")) {
            JsonObject altered = JsonParser.parseString(originalMetadata).getAsJsonObject();JsonObject node = altered.getAsJsonObject("cloneAllocation");
            String[] parts = path.split("\\.");
            for (int index = 0; index < parts.length - 1; index++) node = node.getAsJsonObject(parts[index]);
            node.addProperty(parts[parts.length - 1], path.endsWith("Id") || path.endsWith(".id") ? "999" : uuid(999));
            metadata.set(altered.toString());Assert.assertThrows(RuntimeException.class, this::create);
            Assert.assertEquals(originalDeployments, deployments);
        }
        JsonObject altered = JsonParser.parseString(originalMetadata).getAsJsonObject();
        altered.getAsJsonObject("cloneAllocation").addProperty("unknownAuthority", true);metadata.set(altered.toString());
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(originalDeployments, deployments);
    }
    @Test public void expiredNewPermitBlocksBeforeSharedFsAllocation() {
        denyNewPermit = true;Assert.assertThrows(RuntimeException.class, this::create);
        Assert.assertEquals(0, allocations);Assert.assertEquals(0, deployments);
    }
    @Test public void genericMetadataUpdateCannotEraseRecordedVmReceipt() throws Exception {
        failAfterVmCommit = true;Assert.assertThrows(RuntimeException.class, this::create);JsonObject original = receipt().deepCopy();
        StorageServiceConfiguration configuration = new StorageServiceConfiguration(manager, artifacts,
                Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class), Mockito.mock(StorageConfigArtifactStore.class));
        java.lang.reflect.Method update = StorageServiceConfiguration.class.getDeclaredMethod("update", StorageConfigArtifactVO.class, JsonObject.class, String.class);
        update.setAccessible(true);JsonObject stale = new JsonObject();stale.addProperty("phase", "CONTROLLED_STALE");
        update.invoke(configuration, artifact(), stale, "PARTIAL");Assert.assertEquals(original, receipt());
    }    @Test public void failedVmReceiptRollsBackVmAndDisksBeforeStart() {
        denyVmPublication = true;Assert.assertThrows(RuntimeException.class, this::create);
        Assert.assertTrue(sharedPresent);Assert.assertFalse(receipt().has("vm"));
        Assert.assertNull(vmId.get());Assert.assertNull(dataId.get());Assert.assertEquals(0, starts);
        denyVmPublication = false;Assert.assertSame(target, create());Assert.assertEquals(1, allocations);
    }
    @Test public void numericReceiptCoercionIsRejectedBeforeExistingVmResume() {
        failAfterVmCommit = true;Assert.assertThrows(RuntimeException.class, this::create);failAfterVmCommit = false;
        JsonObject altered = JsonParser.parseString(metadata.get()).getAsJsonObject();
        altered.getAsJsonObject("cloneAllocation").getAsJsonObject("sharedFs").addProperty("accountId", 2.0);
        metadata.set(altered.toString());int count = deployments;
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(count, deployments);
    }
    @Test public void partialVmDbAllocationFailureRollsBackAllIdsBeforeRecorderAndStart() {
        failDuringVmAllocation = true;Assert.assertThrows(RuntimeException.class, this::create);
        Assert.assertTrue(sharedPresent);Assert.assertFalse(receipt().has("vm"));
        Assert.assertNull(vmId.get());Assert.assertNull(dataId.get());Assert.assertEquals(0, starts);
        failDuringVmAllocation = false;Assert.assertSame(target, create());Assert.assertEquals(1, allocations);
    }
    @Test public void erroredOrThinRecordedRootBlocksBeforeAnyResumeCanRecreateIt() {
        failAfterVmCommit = true;Assert.assertThrows(RuntimeException.class, this::create);failAfterVmCommit = false;
        int count = deployments;
        Mockito.when(rootDisk.getState()).thenReturn(Volume.State.Destroy);
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(count, deployments);
        Mockito.when(rootDisk.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(rootDisk.getProvisioningType()).thenReturn(Storage.ProvisioningType.THIN);
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(count, deployments);
        Mockito.when(rootDisk.getProvisioningType()).thenReturn(Storage.ProvisioningType.SPARSE);
        Mockito.when(rootDisk.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(count, deployments);
    }
    @Test public void genuineQcow2ReceiptAllowsOnlyExactReadyRbdRealization() {
        failAfterVmCommit = true;Assert.assertThrows(RuntimeException.class, this::create);failAfterVmCommit = false;
        JsonObject allocation = receipt().deepCopy();
        Mockito.when(formatPool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        Mockito.when(rootDisk.getState()).thenReturn(Volume.State.Ready);Mockito.when(dataDisk.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(rootDisk.getPoolId()).thenReturn(80L);Mockito.when(dataDisk.getPoolId()).thenReturn(80L);
        Mockito.when(rootDisk.getFormat()).thenReturn(Storage.ImageFormat.RAW);Mockito.when(dataDisk.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        Assert.assertSame(target, create());Assert.assertEquals(allocation, receipt());Assert.assertEquals(1, vmAllocations);
        int before = deployments;
        Mockito.when(formatPool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(before, deployments);
        Mockito.when(formatPool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        Mockito.when(rootDisk.getState()).thenReturn(Volume.State.Allocated);
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(before, deployments);
    }
    @Test public void firstRawCaptureOrMissingOriginalFormatPolicyNeverAdoptsTransition() {
        Mockito.when(formatPool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        Mockito.when(rootDisk.getState()).thenReturn(Volume.State.Ready);Mockito.when(rootDisk.getPoolId()).thenReturn(80L);
        Mockito.when(rootDisk.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertFalse(receipt().has("vm"));Assert.assertEquals(0, starts);
        Mockito.when(rootDisk.getState()).thenReturn(Volume.State.Allocated);Mockito.when(rootDisk.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        failAfterVmCommit = true;Assert.assertThrows(RuntimeException.class, this::create);failAfterVmCommit = false;
        JsonObject changed = JsonParser.parseString(metadata.get()).getAsJsonObject();
        changed.getAsJsonObject("cloneAllocation").getAsJsonObject("vm").getAsJsonObject("root").remove("formatPolicy");metadata.set(changed.toString());
        Mockito.when(rootDisk.getState()).thenReturn(Volume.State.Ready);Mockito.when(rootDisk.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        int before = deployments;Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(before, deployments);
    }
    @Test public void initialNicMacConcreteIpDefaultDeviceAndModeDriftRejectBeforeResume() {
        failAfterVmCommit = true;Assert.assertThrows(RuntimeException.class, this::create);failAfterVmCommit = false;
        int before = deployments;
        Mockito.when(allocationNic.getIPv4Address()).thenReturn("10.1.0.10");
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(before, deployments);
        Mockito.when(allocationNic.getIPv4Address()).thenReturn("10.1.0.9");Mockito.when(allocationNic.getMacAddress()).thenReturn("02:00:00:00:00:98");
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(before, deployments);
        Mockito.when(allocationNic.getMacAddress()).thenReturn("02:00:00:00:00:99");Mockito.when(allocationNic.isDefaultNic()).thenReturn(false);
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(before, deployments);
        Mockito.when(allocationNic.isDefaultNic()).thenReturn(true);Mockito.when(allocationNic.getDeviceId()).thenReturn(2);
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(before, deployments);
    }
    @Test public void directNullableAddressesPublishOnceThenRejectChangedAssignment() {
        Mockito.when(allocationNetwork.getGuruName()).thenReturn("DirectNetworkGuru");
        Mockito.when(allocationNic.getIPv4Address()).thenReturn(null);Mockito.when(allocationNic.getIPv6Address()).thenReturn(null);
        Mockito.when(allocationNic.getAddressFormat()).thenReturn(null);
        failAfterVmCommit = true;Assert.assertThrows(RuntimeException.class, this::create);failAfterVmCommit = false;
        Mockito.when(allocationNic.getIPv4Address()).thenReturn("10.1.0.20");
        Mockito.when(allocationNic.getAddressFormat()).thenReturn(com.cloud.network.Networks.AddressFormat.Ip4);
        Assert.assertSame(target, create());
        JsonObject realized = JsonParser.parseString(metadata.get()).getAsJsonObject().getAsJsonObject("cloneAllocationRealizations");
        Assert.assertEquals("10.1.0.20", realized.getAsJsonObject("nic").get("ipv4Address").getAsString());
        int before = deployments;
        Mockito.when(allocationNic.getIPv4Address()).thenReturn("10.1.0.21");
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(before, deployments);
    }
    @Test public void firstReadyRootPoolIsPinnedIndependentlyAndLaterAllowedPoolDriftRejects() {
        failAfterVmCommit = true;Assert.assertThrows(RuntimeException.class, this::create);failAfterVmCommit = false;
        Mockito.when(rootDisk.getState()).thenReturn(Volume.State.Ready);Mockito.when(rootDisk.getPoolId()).thenReturn(80L);
        preservePartialReady = true;Assert.assertThrows(RuntimeException.class, this::create);
        JsonObject realized = JsonParser.parseString(metadata.get()).getAsJsonObject().getAsJsonObject("cloneAllocationRealizations");
        Assert.assertTrue(realized.has("root"));Assert.assertFalse(realized.has("data"));
        org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao pools =
                (org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao) ReflectionTestUtils.getField(manager, "configurationStoragePoolDao");
        org.apache.cloudstack.storage.datastore.db.StoragePoolVO alternate = Mockito.mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class);
        Mockito.when(alternate.getId()).thenReturn(81L);Mockito.when(alternate.getUuid()).thenReturn(uuid(81));
        Mockito.when(alternate.getDataCenterId()).thenReturn(1L);Mockito.when(alternate.getStatus()).thenReturn(com.cloud.storage.StoragePoolStatus.Up);
        Mockito.when(alternate.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);Mockito.when(alternate.isShared()).thenReturn(true);
        Mockito.when(pools.findById(81L)).thenReturn(alternate);Mockito.when(rootDisk.getPoolId()).thenReturn(81L);
        int before = deployments;Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(before, deployments);
        Assert.assertEquals(realized, JsonParser.parseString(metadata.get()).getAsJsonObject().getAsJsonObject("cloneAllocationRealizations"));
    }
    @Test public void realizationPublicationFailurePreventsSuccessAndPreservesOriginalReceipt() {
        failAfterVmCommit = true;Assert.assertThrows(RuntimeException.class, this::create);failAfterVmCommit = false;
        Mockito.when(rootDisk.getState()).thenReturn(Volume.State.Ready);Mockito.when(rootDisk.getPoolId()).thenReturn(80L);
        JsonObject allocation = receipt().deepCopy();denyPublication = true;int before = deployments;
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(before, deployments);
        Assert.assertEquals(allocation, receipt());
        Assert.assertFalse(JsonParser.parseString(metadata.get()).getAsJsonObject().has("cloneAllocationRealizations"));
        denyPublication = false;Assert.assertSame(target, create());
    }
    @Test public void actualStartThrowCapturesReadyRootDespiteTransientDataAndNeverReturnsSuccess() {
        failStartWithPartialRootReady = true;
        Assert.assertThrows(RuntimeException.class, this::create);
        Assert.assertEquals(1, starts);Assert.assertEquals(1, vmAllocations);
        JsonObject metadataAfter = JsonParser.parseString(metadata.get()).getAsJsonObject();
        JsonObject observed = metadataAfter.getAsJsonObject("cloneAllocationRealizations");
        Assert.assertTrue(observed.has("root"));Assert.assertFalse(observed.has("data"));
        Assert.assertEquals(501L, observed.getAsJsonObject("root").get("volumeId").getAsLong());
        Assert.assertEquals(80L, observed.getAsJsonObject("root").get("poolId").getAsLong());
        Assert.assertEquals(7L, receipt().getAsJsonObject("vm").get("vmId").getAsLong());
        int count = starts;
        Assert.assertThrows(RuntimeException.class, this::create);Assert.assertEquals(count, starts);
    }

}

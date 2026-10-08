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
package org.apache.cloudstack.storage.dataservice;

import java.util.*;
import com.google.gson.*;
import com.cloud.storage.*;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.*;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.*;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.junit.*;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageTemplateUpgradeRuntimeTest {
    private static final class Manager extends StorageServiceManagerImpl {
        JsonObject topology=new JsonObject();List<String> commands=new ArrayList<>();List<StorageServiceInstance.Protocol> protocols=new ArrayList<>();
        boolean wrongFilesystem;boolean failQuiesce;int verified;
        @Override protected JsonObject rootTopology(StorageServiceInstanceVO instance){return topology.deepCopy();}
        @Override protected JsonObject createFileShareVolumePayload(StorageServiceInstanceVO instance,StorageFileShareVO share,VolumeVO volume){
            JsonObject payload=new JsonObject();payload.addProperty("volumeUuid",volume.getUuid());return payload;
        }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout){
            commands.add(command);
            if ("operation quiesce".equals(command) && failQuiesce) throw new CloudRuntimeException("QGA boot failure");
            JsonObject result=new JsonObject();result.addProperty("success",true);
            if ("volume attach inspect".equals(command)) {
                Assert.assertEquals("MOUNT_EXISTING",request.get("importMode").getAsString());
                result.add("volumeUuid",request.get("volumeUuid"));result.addProperty("filesystemUuid",wrongFilesystem?"changed":"fs-"+request.get("volumeUuid").getAsString());
            }
            return result;
        }
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action){
            commands.add("generation "+action);JsonObject result=new JsonObject();result.addProperty("success",true);result.add("generation",new JsonObject());return result;
        }
        @Override protected void verifyReconciledStorageDesiredState(StorageServiceInstanceVO instance){verified++;}
        @Override protected void applyStorageServiceProtocolDesiredState(StorageServiceInstanceVO instance,StorageServiceInstance.Protocol protocol){protocols.add(protocol);}
    }
    private Manager manager;private StorageServiceInstanceVO instance;private SharedFSVO shared;private StorageServiceTemplateUpgradeVO row;private StorageServiceOperationVO operation;
    private VolumeDao volumes;private UserVmDao vms;private VolumeService volumeService;private StorageServiceTemplateUpgradeDao upgrades;
    @Before public void setup() {
        manager=new Manager();volumes=Mockito.mock(VolumeDao.class);vms=Mockito.mock(UserVmDao.class);volumeService=Mockito.mock(VolumeService.class);upgrades=Mockito.mock(StorageServiceTemplateUpgradeDao.class);
        instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(6L);Mockito.when(instance.getVmId()).thenReturn(7L);Mockito.when(instance.getUuid()).thenReturn(UUID.randomUUID().toString());Mockito.when(instance.getAccountId()).thenReturn(2L);Mockito.when(instance.getDataCenterId()).thenReturn(5L);
        shared=Mockito.mock(SharedFSVO.class);row=new StorageServiceTemplateUpgradeVO();row.setInstanceId(6);row.setPreviousRootVolumeId(10);row.setTargetRootVolumeId(20L);row.setSourceTemplateId(41);row.setTargetTemplateId(99);row.setPreviousGuestOsId(3);row.setRootDeviceId(0);row.setState("RUNNING");
        operation=new StorageServiceOperationVO();operation.setRevision(10);operation.setInstanceId(6);
        JsonObject snapshot=new JsonObject();snapshot.add("topology",manager.topology.deepCopy());JsonObject generation=new JsonObject();generation.addProperty("revision",9);generation.addProperty("instanceUuid",instance.getUuid());generation.addProperty("operationUuid",UUID.randomUUID().toString());snapshot.add("sourceGeneration",generation);
        JsonObject desired=new JsonObject();desired.add("desired-state/nfs-export-apply.json",new JsonObject());desired.add("desired-state/smb-share-apply.json",new JsonObject());desired.add("iscsi-targets.json",JsonNull.INSTANCE);desired.add("nvmeof-subsystems.json",JsonNull.INSTANCE);snapshot.add("sourceDesiredState",desired);snapshot.add("identity",new JsonObject());row.setSnapshotJson(snapshot.toString());
        ReflectionTestUtils.setField(manager,"volumeDao",volumes);ReflectionTestUtils.setField(manager,"rootUpgradeVmDao",vms);ReflectionTestUtils.setField(manager,"rootUpgradeVolumeService",volumeService);ReflectionTestUtils.setField(manager,"storageTemplateUpgradeDao",upgrades);
        StorageServiceProtocolDao protocols=Mockito.mock(StorageServiceProtocolDao.class);Mockito.when(protocols.listByInstanceId(6L)).thenReturn(List.of());ReflectionTestUtils.setField(manager,"storageServiceProtocolDao",protocols);
        StoragePosixDirectoryPolicyDao policies=Mockito.mock(StoragePosixDirectoryPolicyDao.class);Mockito.when(policies.listByInstance(6L)).thenReturn(List.of());ReflectionTestUtils.setField(manager,"storagePosixPolicyDao",policies);
        StorageFileShareDao shares=Mockito.mock(StorageFileShareDao.class);Mockito.when(shares.listByInstanceIdAndProtocol(Mockito.eq(6L),Mockito.any())).thenReturn(List.of());ReflectionTestUtils.setField(manager,"storageFileShareDao",shares);
        UserVmVO targetVm=Mockito.mock(UserVmVO.class);Mockito.when(targetVm.getTemplateId()).thenReturn(99L);Mockito.when(vms.findById(7L)).thenReturn(targetVm);
        VolumeVO targetRoot=Mockito.mock(VolumeVO.class);Mockito.when(targetRoot.getId()).thenReturn(20L);Mockito.when(targetRoot.getTemplateId()).thenReturn(99L);Mockito.when(targetRoot.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(volumes.findById(20L)).thenReturn(targetRoot);Mockito.when(volumes.findByInstanceAndType(7L,Volume.Type.ROOT)).thenReturn(List.of(targetRoot));
        Mockito.when(upgrades.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
    }
    private StorageServiceManagerImpl.RootUpgradeRuntime runtime(){return manager.new RootUpgradeRuntime(instance,shared,row,operation,false);}
    private StorageFileShareVO share(long id,StorageServiceInstance.Protocol protocol,String uuid){
        VolumeVO volume=Mockito.mock(VolumeVO.class);Mockito.when(volume.getUuid()).thenReturn(uuid);Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);Mockito.when(volumes.findById(id)).thenReturn(volume);
        StorageFileShareVO share=Mockito.mock(StorageFileShareVO.class);Mockito.when(share.getVolumeId()).thenReturn(id);Mockito.when(share.getConfigJson()).thenReturn("{\"filesystemUuid\":\"fs-"+uuid+"\"}");return share;
    }
    @Test public void freshRootMountsEveryExistingBackingWithoutFormattingBeforeGenerationAdoption(){
        StorageFileShareDao shares=(StorageFileShareDao)ReflectionTestUtils.getField(manager,"storageFileShareDao");
        StorageFileShareVO nfs=share(30,StorageServiceInstance.Protocol.NFS,"nfs-data");Mockito.when(shares.listByInstanceIdAndProtocol(6L,StorageServiceInstance.Protocol.NFS)).thenReturn(List.of(nfs));
        StorageFileShareVO smb=share(31,StorageServiceInstance.Protocol.SMB,"smb-data");Mockito.when(shares.listByInstanceIdAndProtocol(6L,StorageServiceInstance.Protocol.SMB)).thenReturn(List.of(smb));
        runtime().reconcile();Assert.assertEquals(List.of(StorageServiceInstance.Protocol.NFS,StorageServiceInstance.Protocol.SMB),manager.protocols);
        Assert.assertEquals(2,Collections.frequency(manager.commands,"volume attach inspect"));Assert.assertTrue(manager.verified>0);
        Assert.assertTrue(manager.commands.indexOf("operation generation adopt")>manager.commands.lastIndexOf("volume attach inspect"));
        Assert.assertTrue(manager.commands.indexOf("generation begin")>manager.commands.indexOf("operation generation adopt"));Mockito.verifyNoInteractions(volumeService);
    }
    @Test public void filesystemMismatchStopsBeforeProtocolApplyAndNativeGenerationCommit(){
        StorageFileShareDao shares=(StorageFileShareDao)ReflectionTestUtils.getField(manager,"storageFileShareDao");StorageFileShareVO nfs=share(30,StorageServiceInstance.Protocol.NFS,"nfs-data");Mockito.when(shares.listByInstanceIdAndProtocol(6L,StorageServiceInstance.Protocol.NFS)).thenReturn(List.of(nfs));
        manager.wrongFilesystem=true;Assert.assertThrows(CloudRuntimeException.class,()->runtime().reconcile());Assert.assertTrue(manager.protocols.isEmpty());Assert.assertFalse(manager.commands.contains("generation begin"));Mockito.verifyNoInteractions(volumeService);
    }
    @Test public void topologyDriftStopsBeforeAnyMountOrProtocolMutation(){
        manager.topology.addProperty("changedNic","bad");Assert.assertThrows(CloudRuntimeException.class,()->runtime().reconcile());Assert.assertTrue(manager.commands.isEmpty());Assert.assertTrue(manager.protocols.isEmpty());
    }
    @Test public void explicitFinalizeCannotDestroyAttachedRootOrAnyDataDisk(){
        row.setState("COMPLETE");row.setRollbackRetainUntil(new Date(System.currentTimeMillis()-1000));VolumeVO discarded=Mockito.mock(VolumeVO.class);Mockito.when(volumes.findById(10L)).thenReturn(discarded);
        Mockito.when(discarded.getVolumeType()).thenReturn(Volume.Type.DATADISK);Assert.assertThrows(InvalidParameterValueException.class,()->manager.finalizeTemplateUpgrade(instance,row));
        Mockito.when(discarded.getVolumeType()).thenReturn(Volume.Type.ROOT);Mockito.when(discarded.getInstanceId()).thenReturn(7L);Assert.assertThrows(InvalidParameterValueException.class,()->manager.finalizeTemplateUpgrade(instance,row));Mockito.verifyNoInteractions(volumeService);
    }
    @Test public void retainedRootCannotBeFinalizedBeforeItsRetentionExpires(){
        row.setState("COMPLETE");row.setRollbackRetainUntil(new Date(System.currentTimeMillis()+60000));Assert.assertThrows(InvalidParameterValueException.class,()->manager.finalizeTemplateUpgrade(instance,row));Mockito.verifyNoInteractions(volumeService);Mockito.verify(volumes,Mockito.never()).findById(10L);
    }

    @Test public void wrongCurrentRootCannotReceiveIdentityDesiredSeedOrGenerationCommit(){
        Mockito.when(volumes.findByInstanceAndType(7L,Volume.Type.ROOT)).thenReturn(List.of());
        Assert.assertThrows(CloudRuntimeException.class,()->runtime().reconcile());Assert.assertTrue(manager.commands.isEmpty());Assert.assertTrue(manager.protocols.isEmpty());
    }
}

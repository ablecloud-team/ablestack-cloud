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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.NicVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StoragePosixDirectoryPolicyDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageTemplateUpgradeRuntimeTest {
    private static final class Manager extends StorageServiceManagerImpl {
        JsonObject topology=new JsonObject();JsonObject dataManifest=new JsonObject();List<String> commands=new ArrayList<>();List<StorageServiceInstance.Protocol> protocols=new ArrayList<>();
        JsonObject maintenanceScope;JsonObject lastQuiesce;boolean releaseFailure;JsonObject desired;StorageServiceOperationVO rootOperation;
        @Override protected RenderedBatch restoreRenderedBatch(StorageServiceOperationVO operation){rootOperation=operation;JsonObject before=new JsonObject();before.add("generation",new JsonObject());before.addProperty("configurationSha256","a".repeat(64));before.add("configurationDesiredState",new JsonObject());JsonObject manifest=new JsonObject();manifest.addProperty("manifestSha256","b".repeat(64));RenderedBatch batch=new RenderedBatch(operation,before,manifest,StorageIdentityCapsule.wrappingKey());return batch;}
        @Override protected RenderedBatch prepareRootRenderedBaseline(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,JsonObject scope,JsonObject source){commands.add("ROOT_EMPTY_BASELINE_IMPORT");return restoreRenderedBatch(operation);}
        @Override protected void stageRootRendered(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,RenderedBatch batch,JsonObject canonical){
            if(canonical.has("network-endpoints.json")&&canonical.get("network-endpoints.json").isJsonObject()){rootNetworkBindings(instance,canonical.getAsJsonObject("network-endpoints.json"));if(foreignEndpoint)throw new CloudRuntimeException("Native endpoint MAC readback differs");}commands.add("ROOT_RENDER_STAGE");commands.add("ROOT_RENDER_ACTIVATE");verified++;desired=canonical.deepCopy();for(StorageServiceInstance.Protocol protocol:StorageServiceInstance.Protocol.values()){String path=StorageRenderedDesiredState.PROTOCOL_PATHS.get(protocol);if(canonical.has(path)&&!canonical.get(path).isJsonNull())protocols.add(protocol);}batch.receipt=new JsonObject();batch.receipt.addProperty("phase","VERIFIED");}
        @Override protected StorageRenderedGenerationCoordinator.Runtime renderedRuntime(StorageServiceInstanceVO instance,RenderedBatch batch) {
            JsonObject scope=new JsonObject();scope.addProperty("instanceUuid",instance.getUuid());scope.addProperty("operationUuid",batch.operation.getUuid());scope.addProperty("revision",batch.operation.getRevision());
            if(batch.receipt==null){batch.receipt=new JsonObject();batch.receipt.addProperty("phase","VERIFIED");batch.receipt.add("previousGeneration",new JsonObject());batch.receipt.addProperty("previousRenderedSha256","b".repeat(64));JsonObject staged=new JsonObject();staged.addProperty("renderedManifestSha256","a".repeat(64));staged.addProperty("configurationSha256","a".repeat(64));JsonObject checkpoint=new JsonObject();checkpoint.addProperty("operationUuid",batch.operation.getUuid());checkpoint.addProperty("sha256","c".repeat(64));staged.add("identityCheckpointRef",checkpoint);batch.receipt.add("staged",staged);}
            return new StorageRenderedGenerationCoordinator.Runtime(){boolean committed;
                public JsonObject render(String action,JsonObject request){commands.add("operation generation render-"+action);JsonObject result=new JsonObject();result.addProperty("success",true);result.addProperty("bootHeld",!action.equals("finalize"));JsonObject current=new JsonObject();current.add("scope",scope.deepCopy());current.addProperty("manifestSha256","a".repeat(64));current.addProperty("configurationSha256","a".repeat(64));result.add("current",current);JsonObject activation=new JsonObject();activation.add("scope",scope.deepCopy());activation.addProperty("phase",action.equals("finalize")?"COMPLETE":"VERIFIED");activation.addProperty("targetSha256","a".repeat(64));activation.addProperty("previousSha256","b".repeat(64));result.add("activation",activation);return result;}
                public JsonObject nativeGenerationStatus(){JsonObject result=new JsonObject();result.addProperty("success",true);result.addProperty("generationSupported",true);JsonObject generation=committed?scope.deepCopy():new JsonObject();if(committed)generation.addProperty("configurationSha256","a".repeat(64));result.add("generation",generation);result.add("pendingOperationUuid",committed?JsonNull.INSTANCE:new com.google.gson.JsonPrimitive(batch.operation.getUuid()));result.addProperty("generationStatus",committed?"IN_SYNC":"PENDING");result.addProperty("configurationSha256","a".repeat(64));return result;}
                public void save(JsonObject receipt){batch.receipt=receipt;}public void requireAvailable(){ }public void verifyAllProtocols(){verified++;}public void commitGeneration(JsonObject receipt){commands.add("ROOT_NATIVE_COMMIT");committed=true;}public void rollbackGeneration(JsonObject receipt){ }public void releaseMaintenance(){ }public void promote(){ }
            };
        }

        boolean wrongFilesystem;boolean foreignEndpoint;boolean failQuiesce;boolean failAfterQuiesce;boolean wrongEnteredScope;int verified;
        @Override protected JsonObject rootTopology(StorageServiceInstanceVO instance){return topology.deepCopy();}
        @Override protected JsonObject inspectRootData(StorageServiceInstanceVO instance,SharedFSVO shared,String operation,String upgrade){return dataManifest.deepCopy();}
        @Override protected JsonObject createFileShareVolumePayload(StorageServiceInstanceVO instance,StorageFileShareVO share,VolumeVO volume){
            JsonObject payload=new JsonObject();payload.addProperty("volumeUuid",volume.getUuid());return payload;
        }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout){
            commands.add(command);
            if ("operation quiesce".equals(command) && failQuiesce) throw new CloudRuntimeException("QGA boot failure");
            JsonObject result=new JsonObject();result.addProperty("success",true);
            if("operation maintenance status".equals(command)){result.addProperty("maintenanceSupported",true);result.add("scope",maintenanceScope==null?JsonNull.INSTANCE:maintenanceScope.deepCopy());}
            if("operation quiesce".equals(command)){result.addProperty("quiesced",true);result.addProperty("bootHeld",true);lastQuiesce=request.deepCopy();maintenanceScope=request.deepCopy();maintenanceScope.remove("expectedPreviousScope");result.add("scope",maintenanceScope.deepCopy());if(wrongEnteredScope)result.getAsJsonObject("scope").addProperty("operationUuid",UUID.randomUUID().toString());if(failAfterQuiesce)throw new CloudRuntimeException("response lost after native enter");}
            if("operation maintenance release".equals(command)){if(releaseFailure)throw new CloudRuntimeException("release still held");result.addProperty("released",true);result.addProperty("bootHeld",false);maintenanceScope=null;}
            if ("volume attach inspect".equals(command)) {
                Assert.assertEquals("MOUNT_EXISTING",request.get("importMode").getAsString());
                result.add("volumeUuid",request.get("volumeUuid"));result.addProperty("filesystemUuid",wrongFilesystem?"changed":"fs-"+request.get("volumeUuid").getAsString());
            }
            if ("network endpoints reconcile".equals(command)) {
                JsonArray endpoints=new JsonArray();for(JsonElement entry:request.getAsJsonArray("expectedBindings")) {JsonObject endpoint=entry.getAsJsonObject().deepCopy();endpoint.addProperty("active",true);if(foreignEndpoint)endpoint.addProperty("macAddress","00:00:00:00:00:ff");endpoints.add(endpoint);}result.add("endpoints",endpoints);
            }
            return result;
        }
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action){
            commands.add("generation "+action);JsonObject result=new JsonObject();result.addProperty("success",true);result.addProperty("generationStatus","IN_SYNC");result.add("generation",new JsonObject());if(desired!=null)result.add("configurationDesiredState",desired.deepCopy());return result;
        }
        @Override protected void verifyReconciledStorageDesiredState(StorageServiceInstanceVO instance){verified++;}
        @Override protected void applyStorageServiceProtocolDesiredState(StorageServiceInstanceVO instance,StorageServiceInstance.Protocol protocol){commands.add("APPLY_"+protocol.name());protocols.add(protocol);}
    }
    private Manager manager;private StorageServiceInstanceVO instance;private SharedFSVO shared;private StorageServiceTemplateUpgradeVO row;private StorageServiceOperationVO operation;
    private final List<VolumeVO> attachments=new ArrayList<>();
    private VolumeDao volumes;private UserVmDao vms;private VolumeService volumeService;private StorageServiceTemplateUpgradeDao upgrades;
    @Before public void setup() {
        manager=new Manager();manager.dataManifest.add("volumes",new JsonArray());volumes=Mockito.mock(VolumeDao.class);vms=Mockito.mock(UserVmDao.class);volumeService=Mockito.mock(VolumeService.class);upgrades=Mockito.mock(StorageServiceTemplateUpgradeDao.class);
        instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(6L);Mockito.when(instance.getVmId()).thenReturn(7L);Mockito.when(instance.getUuid()).thenReturn(UUID.randomUUID().toString());Mockito.when(instance.getAccountId()).thenReturn(2L);Mockito.when(instance.getDataCenterId()).thenReturn(5L);
        shared=Mockito.mock(SharedFSVO.class);row=new StorageServiceTemplateUpgradeVO();row.setInstanceId(6);row.setPreviousRootVolumeId(10);row.setTargetRootVolumeId(20L);row.setSourceTemplateId(41);row.setTargetTemplateId(99);row.setPreviousGuestOsId(3);row.setRootDeviceId(0);row.setState("RUNNING");
        operation=new StorageServiceOperationVO();operation.setRevision(10);operation.setInstanceId(6);
        JsonObject snapshot=new JsonObject();snapshot.add("topology",manager.topology.deepCopy());JsonObject generation=new JsonObject();generation.addProperty("revision",9);generation.addProperty("instanceUuid",instance.getUuid());generation.addProperty("operationUuid",UUID.randomUUID().toString());snapshot.add("sourceGeneration",generation);
        JsonObject desired=new JsonObject();desired.add("desired-state/nfs-export-apply.json",new JsonObject());desired.add("desired-state/smb-share-apply.json",new JsonObject());desired.add("iscsi-targets.json",JsonNull.INSTANCE);desired.add("nvmeof-subsystems.json",JsonNull.INSTANCE);snapshot.add("sourceDesiredState",desired);snapshot.add("identity",new JsonObject());snapshot.add("dataManifest",manager.dataManifest.deepCopy());row.setSnapshotJson(snapshot.toString());
        ReflectionTestUtils.setField(manager,"volumeDao",volumes);ReflectionTestUtils.setField(manager,"rootUpgradeVmDao",vms);ReflectionTestUtils.setField(manager,"rootUpgradeVolumeService",volumeService);ReflectionTestUtils.setField(manager,"storageTemplateUpgradeDao",upgrades);
        ReflectionTestUtils.setField(manager,"storageOperationControlDao",Mockito.mock(StorageServiceOperationControlDao.class));
        StorageServiceProtocolDao protocols=Mockito.mock(StorageServiceProtocolDao.class);Mockito.when(protocols.listByInstanceId(6L)).thenReturn(List.of());ReflectionTestUtils.setField(manager,"storageServiceProtocolDao",protocols);
        StoragePosixDirectoryPolicyDao policies=Mockito.mock(StoragePosixDirectoryPolicyDao.class);Mockito.when(policies.listByInstance(6L)).thenReturn(List.of());ReflectionTestUtils.setField(manager,"storagePosixPolicyDao",policies);
        StorageFileShareDao shares=Mockito.mock(StorageFileShareDao.class);Mockito.when(shares.listByInstanceIdAndProtocol(Mockito.eq(6L),Mockito.any())).thenReturn(List.of());ReflectionTestUtils.setField(manager,"storageFileShareDao",shares);
        UserVmVO targetVm=Mockito.mock(UserVmVO.class);Mockito.when(targetVm.getTemplateId()).thenReturn(99L);Mockito.when(targetVm.getUserVmType()).thenReturn(UserVmManager.SHAREDFSVM);Mockito.when(targetVm.getState()).thenReturn(VirtualMachine.State.Running);Mockito.when(vms.findById(7L)).thenReturn(targetVm);
        VolumeVO targetRoot=Mockito.mock(VolumeVO.class);Mockito.when(targetRoot.getId()).thenReturn(20L);Mockito.when(targetRoot.getTemplateId()).thenReturn(99L);Mockito.when(targetRoot.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(volumes.findById(20L)).thenReturn(targetRoot);Mockito.when(volumes.findByInstanceAndType(7L,Volume.Type.ROOT)).thenReturn(List.of(targetRoot));Mockito.when(volumes.findByInstanceAndType(7L,Volume.Type.DATADISK)).thenReturn(attachments);
        Mockito.when(upgrades.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
    }
    private StorageServiceManagerImpl.RootUpgradeRuntime runtime(){return manager.new RootUpgradeRuntime(instance,shared,row,operation,false);}
    private StorageFileShareVO share(long id,StorageServiceInstance.Protocol protocol,String uuid){
        VolumeVO volume=Mockito.mock(VolumeVO.class);Mockito.when(volume.getUuid()).thenReturn(uuid);Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);Mockito.when(volumes.findById(id)).thenReturn(volume);
        Mockito.when(volume.getId()).thenReturn(id);Mockito.when(volume.getSize()).thenReturn(1024L);attachments.add(volume);
        JsonObject identity=new JsonObject();identity.addProperty("volumeUuid",uuid);identity.addProperty("kind","FILE_DATA");identity.addProperty("serial","serial-"+uuid);identity.addProperty("sizeBytes",1024);identity.addProperty("filesystem","xfs");identity.addProperty("filesystemUuid","fs-"+uuid);manager.dataManifest.getAsJsonArray("volumes").add(identity);
        JsonObject frozen=new JsonParser().parse(row.getSnapshotJson()).getAsJsonObject();frozen.add("dataManifest",manager.dataManifest.deepCopy());row.setSnapshotJson(frozen.toString());
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

    private void sourceSecondaryEndpoint(){
        com.cloud.vm.dao.NicDao nics=Mockito.mock(com.cloud.vm.dao.NicDao.class);com.cloud.vm.dao.NicSecondaryIpDao aliases=Mockito.mock(com.cloud.vm.dao.NicSecondaryIpDao.class);NicVO nic=Mockito.mock(NicVO.class);com.cloud.vm.dao.NicSecondaryIpVO alias=Mockito.mock(com.cloud.vm.dao.NicSecondaryIpVO.class);
        Mockito.when(nic.getId()).thenReturn(79L);Mockito.when(nic.getIPv4Address()).thenReturn("10.10.13.240");Mockito.when(nic.getMacAddress()).thenReturn("02:0c:02:f9:00:80");Mockito.when(nics.listByVmId(7L)).thenReturn(List.of(nic));Mockito.when(alias.getNicId()).thenReturn(79L);Mockito.when(alias.getIp4Address()).thenReturn("10.10.13.241");Mockito.when(aliases.listByVmId(7L)).thenReturn(List.of(alias));ReflectionTestUtils.setField(manager,"nicDao",nics);ReflectionTestUtils.setField(manager,"nicSecondaryIpDao",aliases);
        JsonObject value=new JsonParser().parse(row.getSnapshotJson()).getAsJsonObject();JsonObject cache=new JsonObject();JsonArray endpoints=new JsonArray();JsonObject endpoint=new JsonObject();endpoint.addProperty("listenIp","10.10.13.241");endpoint.addProperty("primaryIp","10.10.13.240");endpoint.addProperty("prefixlen",16);endpoints.add(endpoint);cache.add("endpoints",endpoints);value.getAsJsonObject("sourceDesiredState").add("network-endpoints.json",cache);row.setSnapshotJson(value.toString());
    }
    @Test public void shareOnlySecondaryEndpointIsActivatedBeforeListenerReconcileOnItsPreservedMac(){
        sourceSecondaryEndpoint();runtime().reconcile();Assert.assertTrue(manager.commands.contains("ROOT_RENDER_STAGE"));Assert.assertFalse(manager.protocols.isEmpty());Assert.assertTrue(manager.commands.indexOf("ROOT_RENDER_STAGE")<manager.commands.indexOf("ROOT_RENDER_ACTIVATE"));Assert.assertFalse(manager.commands.contains("network endpoints reconcile"));Assert.assertFalse(manager.commands.contains("APPLY_SMB"));
    }
    @Test public void foreignNicEndpointCannotProceedToProtocolApplyOrGenerationAdoption(){
        sourceSecondaryEndpoint();manager.foreignEndpoint=true;Assert.assertThrows(CloudRuntimeException.class,()->runtime().reconcile());Assert.assertTrue(manager.protocols.isEmpty());Assert.assertFalse(manager.commands.contains("operation generation adopt"));
    }

    @Test public void targetDefaultAcceptorsAreQuiescedBeforeSignedRuntimeAndIdentityRecovery(){
        StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"caps","{\"success\":true,\"localIdentity\":true,\"protectedStdinTransport\":true}"));ReflectionTestUtils.setField(manager,"guestCommandDispatcher",guest);
        StorageServiceRuntimeUpgradeManager signed=Mockito.mock(StorageServiceRuntimeUpgradeManager.class);Mockito.when(signed.restoreTemplateRuntime(Mockito.eq(6L),Mockito.any(),Mockito.anyString(),Mockito.eq("target"))).thenAnswer(call->{manager.commands.add("RESTORE_SIGNED_RUNTIME");return new JsonObject();});ReflectionTestUtils.setField(manager,"runtimeUpgradeManager",signed);
        JsonObject value=new JsonParser().parse(row.getSnapshotJson()).getAsJsonObject();JsonObject pin=new JsonObject();pin.add("pin",new JsonObject());value.add("signedRuntime",pin);row.setSnapshotJson(value.toString());
        runtime().bootTarget();Assert.assertTrue(manager.commands.indexOf("operation quiesce")>=0);Assert.assertTrue(manager.commands.indexOf("operation quiesce")<manager.commands.indexOf("RESTORE_SIGNED_RUNTIME"));Assert.assertFalse(manager.commands.contains("volume attach inspect"));Assert.assertTrue(manager.commands.indexOf("ROOT_EMPTY_BASELINE_IMPORT")>manager.commands.indexOf("RESTORE_SIGNED_RUNTIME"));
    }

    private JsonObject maintenanceScope(String op,long revision){JsonObject value=new JsonObject();value.addProperty("instanceUuid",instance.getUuid());value.addProperty("templateUpgradeUuid",row.getUuid());value.addProperty("operationUuid",op);value.addProperty("revision",revision);return value;}
    @Test public void retainedRootMarkerCanOnlyAdoptItsExactKnownPreviousScope(){
        JsonObject old=maintenanceScope(UUID.randomUUID().toString(),9);manager.maintenanceScope=old.deepCopy();JsonObject snapshot=new JsonParser().parse(row.getSnapshotJson()).getAsJsonObject();snapshot.add("sourceMaintenanceScope",old);row.setSnapshotJson(snapshot.toString());
        StorageServiceManagerImpl.RootUpgradeRuntime runtime=runtime();ReflectionTestUtils.invokeMethod(runtime,"enterMaintenance","sourceMaintenanceScope");
        Assert.assertEquals(old,manager.lastQuiesce.getAsJsonObject("expectedPreviousScope"));Assert.assertEquals(operation.getUuid(),manager.lastQuiesce.get("operationUuid").getAsString());
    }
    @Test public void foreignMarkerCannotBeOverwrittenByTheCurrentRootUpgrade(){
        manager.maintenanceScope=maintenanceScope(UUID.randomUUID().toString(),9);manager.maintenanceScope.addProperty("templateUpgradeUuid",UUID.randomUUID().toString());
        Assert.assertThrows(CloudRuntimeException.class,()->ReflectionTestUtils.invokeMethod(runtime(),"enterMaintenance","targetMaintenanceScope"));Assert.assertFalse(manager.commands.contains("operation quiesce"));
    }
    @Test public void maintenanceReleaseFailureCannotCommitTheDatabaseOrClaimComplete(){
        StorageServiceManagerImpl.RootUpgradeRuntime runtime=runtime();ReflectionTestUtils.invokeMethod(runtime,"enterMaintenance","targetMaintenanceScope");manager.releaseFailure=true;
        Assert.assertThrows(CloudRuntimeException.class,()->runtime.commit());Assert.assertEquals("RUNNING",row.getState());Assert.assertTrue(new JsonParser().parse(row.getSnapshotJson()).getAsJsonObject().get("bootHeld").getAsBoolean());Assert.assertTrue(manager.commands.contains("operation generation render-finalize"));Assert.assertTrue(manager.commands.indexOf("operation generation render-finalize")<manager.commands.indexOf("operation maintenance release"));
    }
    @Test public void maintenanceCrashBeforeGuestLeavesKnownOldScopeAndResumesCas() {
        JsonObject old=maintenanceScope(UUID.randomUUID().toString(),9);manager.maintenanceScope=old.deepCopy();JsonObject value=new JsonParser().parse(row.getSnapshotJson()).getAsJsonObject();value.add("sourceMaintenanceScope",old);row.setSnapshotJson(value.toString());
        manager.failQuiesce=true;Assert.assertThrows(CloudRuntimeException.class,()->ReflectionTestUtils.invokeMethod(runtime(),"enterMaintenance","sourceMaintenanceScope"));
        JsonObject persisted=new JsonParser().parse(row.getSnapshotJson()).getAsJsonObject();Assert.assertEquals(old,persisted.get("sourceMaintenanceScope"));Assert.assertTrue(persisted.has("sourceMaintenanceScopeTransition"));Assert.assertEquals(old,manager.maintenanceScope);
        manager.failQuiesce=false;ReflectionTestUtils.invokeMethod(runtime(),"enterMaintenance","sourceMaintenanceScope");Assert.assertEquals(old,manager.lastQuiesce.get("expectedPreviousScope"));Assert.assertFalse(new JsonParser().parse(row.getSnapshotJson()).getAsJsonObject().has("sourceMaintenanceScopeTransition"));
    }
    @Test public void maintenanceCrashAfterGuestResumesAlreadyEnteredScopeWithoutForeignAdoption() {
        JsonObject old=maintenanceScope(UUID.randomUUID().toString(),9);manager.maintenanceScope=old.deepCopy();JsonObject value=new JsonParser().parse(row.getSnapshotJson()).getAsJsonObject();value.add("sourceMaintenanceScope",old);row.setSnapshotJson(value.toString());
        manager.failAfterQuiesce=true;Assert.assertThrows(CloudRuntimeException.class,()->ReflectionTestUtils.invokeMethod(runtime(),"enterMaintenance","sourceMaintenanceScope"));
        Assert.assertEquals(old,new JsonParser().parse(row.getSnapshotJson()).getAsJsonObject().get("sourceMaintenanceScope"));Assert.assertEquals(operation.getUuid(),manager.maintenanceScope.get("operationUuid").getAsString());
        manager.failAfterQuiesce=false;ReflectionTestUtils.invokeMethod(runtime(),"enterMaintenance","sourceMaintenanceScope");Assert.assertFalse(manager.lastQuiesce.has("expectedPreviousScope"));Assert.assertEquals(manager.maintenanceScope,new JsonParser().parse(row.getSnapshotJson()).getAsJsonObject().get("sourceMaintenanceScope"));
    }

    @Test public void successfulQuiesceBooleansCannotPromoteAMismatchedNativeScope() {
        manager.wrongEnteredScope=true;Assert.assertThrows(CloudRuntimeException.class,()->ReflectionTestUtils.invokeMethod(runtime(),"enterMaintenance","targetMaintenanceScope"));
        JsonObject persisted=new JsonParser().parse(row.getSnapshotJson()).getAsJsonObject();Assert.assertFalse(persisted.has("targetMaintenanceScope"));Assert.assertTrue(persisted.has("targetMaintenanceScopeTransition"));
    }

}

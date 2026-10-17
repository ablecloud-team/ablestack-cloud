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
import java.util.List;
import java.util.UUID;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class StorageCanonicalRecoveryTest {
    private static final String VOLUME = "076eae6e-b0b5-4a8a-885f-b594454bc925";
    private static final String NAMESPACE = "d6148c6f-6413-457e-8516-48f6a69819c3";
    private static final String NQN = "nqn.2026-10.local.storage:source";
    private static final long SIZE = 2L << 30;

    private static class Manager extends StorageServiceManagerImpl {
        JsonObject live, inventory, source;
        VolumeVO volume;
        boolean race, wrongRestore;
        int statuses, restores;
        final List<String> calls = new ArrayList<>();
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance, StorageServiceOperationVO operation, String action) {
            Assert.assertEquals("status", action);statuses++;
            if (race && statuses == 2) live.addProperty("bootId", UUID.randomUUID().toString());
            return live.deepCopy();
        }
        @Override protected VolumeVO requireVolume(Long id) {Assert.assertEquals(Long.valueOf(126), id);return volume;}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance, String command, JsonObject request, int timeout) {
            calls.add(command);
            if ("operation observe".equals(command)) return inventory.deepCopy();
            Assert.assertEquals("operation generation restore", command);
            restores++;Assert.assertEquals(source, request.get("configurationDesiredState"));
            Assert.assertEquals("/dev/sdb", runtime(inventory).get("backingPath").getAsString());
            JsonObject result = new JsonObject();result.addProperty("canonicalRestored", true);result.addProperty("generationAdvanced", false);
            result.add("pendingOperationUuid", request.get("operationUuid"));result.addProperty("configurationSha256", (wrongRestore ? "c" : "a").repeat(64));
            live.add("configurationDesiredState", source.deepCopy());live.addProperty("configurationSha256", "a".repeat(64));return result;
        }
    }

    private Manager manager;
    private StorageServiceInstanceVO instance;
    private StorageServiceOperationVO operation;
    private JsonObject source, context, frozen;

    private static JsonObject namespace(JsonObject desired) {
        return desired.getAsJsonObject("nvmeof-subsystems.json").getAsJsonArray("subsystems").get(0).getAsJsonObject()
                .getAsJsonArray("namespaces").get(0).getAsJsonObject();
    }
    private static JsonObject runtime(JsonObject inventory) {
        return inventory.getAsJsonObject("nvmeofSubsystems").getAsJsonArray("subsystems").get(0).getAsJsonObject()
                .getAsJsonArray("namespaces").get(0).getAsJsonObject().getAsJsonObject("runtime");
    }
    private static JsonObject object(String key, String value) {JsonObject result = new JsonObject();result.addProperty(key, value);return result;}
    private static JsonArray array(JsonObject value) {JsonArray result = new JsonArray();result.add(value);return result;}

    @Before public void setup() {
        manager = new Manager();instance = Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(instance.getId()).thenReturn(29L);Mockito.when(instance.getUuid()).thenReturn(UUID.randomUUID().toString());
        Mockito.when(instance.getVmId()).thenReturn(78L);Mockito.when(instance.getAccountId()).thenReturn(2L);
        Mockito.when(instance.getDomainId()).thenReturn(1L);Mockito.when(instance.getDataCenterId()).thenReturn(1L);
        operation = new StorageServiceOperationVO();operation.setInstanceId(29);operation.setRevision(17);
        source = new JsonObject();for (String path : StorageRenderedDesiredState.PATHS) source.add(path, JsonNull.INSTANCE);
        JsonObject ns = object("uuid", NAMESPACE);ns.addProperty("volumeUuid", VOLUME);ns.addProperty("volumeId", 126);
        ns.addProperty("volumeSizeBytes", SIZE);ns.addProperty("backstoreType", "BLOCK");ns.addProperty("lunOrNamespace", "1");
        ns.add("runtime", object("backingPath", "/dev/sde"));ns.add("config", object("backstoreType", "BLOCK"));
        JsonObject subsystem = object("targetName", NQN);subsystem.add("namespaces", array(ns));
        JsonObject nvme = new JsonObject();nvme.addProperty("enabled", true);nvme.add("subsystems", array(subsystem));source.add("nvmeof-subsystems.json", nvme);
        JsonObject generation = object("instanceUuid", instance.getUuid());generation.addProperty("operationUuid", UUID.randomUUID().toString());
        generation.addProperty("revision", 16);generation.addProperty("configurationSha256", "a".repeat(64));
        frozen = object("configurationSha256", "a".repeat(64));frozen.add("generation", generation);frozen.add("configurationDesiredState", source);
        context = object("instanceUuid", instance.getUuid());context.addProperty("operationUuid", operation.getUuid());context.addProperty("revision", 17);
        context.add("sourceGeneration", generation);context.addProperty("sourceConfigurationSha256", "a".repeat(64));
        context.addProperty("expectedBootId", UUID.randomUUID().toString());
        manager.live = object("bootId", context.get("expectedBootId").getAsString());manager.live.addProperty("success", true);
        manager.live.addProperty("generationSupported", true);manager.live.addProperty("generationStatus", "PENDING");
        manager.live.add("generation", generation);manager.live.addProperty("pendingOperationUuid", operation.getUuid());
        manager.live.addProperty("configurationSha256", "b".repeat(64));manager.live.add("configurationDesiredState", source.deepCopy());
        namespace(manager.live.getAsJsonObject("configurationDesiredState")).getAsJsonObject("runtime").addProperty("backingPath", "/dev/sdb");
        manager.source = source;
        manager.inventory = object("status", "ok");manager.inventory.addProperty("success", true);
        manager.inventory.addProperty("generatedEpoch", System.currentTimeMillis() / 1000.0);
        JsonObject endpoint = new JsonObject();endpoint.addProperty("listening", true);manager.inventory.add("nvmeofRuntime", endpoint);
        JsonObject actual = source.getAsJsonObject("nvmeof-subsystems.json").deepCopy();
        JsonObject actualSubsystem = actual.getAsJsonArray("subsystems").get(0).getAsJsonObject();
        JsonObject presence = new JsonObject();presence.addProperty("configfsPresent", true);actualSubsystem.add("runtime", presence);
        JsonObject runtime = new JsonObject();
        runtime.addProperty("configfsPresent", true);runtime.addProperty("enabled", true);runtime.addProperty("runtimeState", "READY");
        runtime.addProperty("mappingStatus", "EXACT");runtime.addProperty("runtimeVolumeUuid", VOLUME);runtime.addProperty("volumeUuid", VOLUME);
        runtime.addProperty("subsystemNqn", NQN);runtime.addProperty("namespaceId", "1");runtime.addProperty("backingPath", "/dev/sdb");
        runtime.addProperty("resolvedBackingPath", "/dev/sdb");runtime.addProperty("serial", VOLUME.replace("-", "").substring(0, 20));
        runtime.addProperty("actualSizeBytes", SIZE);
        actualSubsystem.getAsJsonArray("namespaces").get(0).getAsJsonObject().add("runtime", runtime);manager.inventory.add("nvmeofSubsystems", actual);
        manager.volume = Mockito.mock(VolumeVO.class);Mockito.when(manager.volume.getId()).thenReturn(126L);
        Mockito.when(manager.volume.getUuid()).thenReturn(VOLUME);Mockito.when(manager.volume.getInstanceId()).thenReturn(78L);
        Mockito.when(manager.volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);Mockito.when(manager.volume.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(manager.volume.getAccountId()).thenReturn(2L);Mockito.when(manager.volume.getDomainId()).thenReturn(1L);
        Mockito.when(manager.volume.getDataCenterId()).thenReturn(1L);Mockito.when(manager.volume.getSize()).thenReturn(SIZE);
    }

    private void normalize() {manager.restoreLocalNvmeCanonicalSource(instance, operation, context, frozen);}

    @Test public void coldBootKernelAliasNormalizesOnlyCanonicalSourceBeforeNfsWithoutChangingRuntimeDevice() {
        normalize();
        Assert.assertEquals(List.of("operation observe", "operation generation restore"), manager.calls);
        Assert.assertEquals(source, manager.live.get("configurationDesiredState"));
        Assert.assertEquals("/dev/sdb", runtime(manager.inventory).get("backingPath").getAsString());
        Assert.assertEquals(1, manager.restores);Assert.assertEquals(3, manager.statuses);
        manager.calls.clear();normalize();Assert.assertTrue(manager.calls.isEmpty());Assert.assertEquals(1, manager.restores);
    }
    @Test public void wrongUuidSerialSizeAndKernelNamespaceCannotAuthorizeCanonicalRewrite() {
        JsonObject valid = manager.inventory.deepCopy();
        for (String field : List.of("runtimeVolumeUuid", "volumeUuid", "serial", "actualSizeBytes", "subsystemNqn", "namespaceId", "backingPath", "resolvedBackingPath", "configfsPresent", "enabled", "mappingStatus")) {
            manager.inventory = valid.deepCopy();JsonObject actual = runtime(manager.inventory);
            if (field.equals("actualSizeBytes")) actual.addProperty(field, SIZE + 1);
            else if (field.equals("enabled") || field.equals("configfsPresent")) actual.addProperty(field, "true");
            else actual.addProperty(field, "foreign");
            Assert.assertThrows(field, CloudRuntimeException.class, this::normalize);Assert.assertEquals(0, manager.restores);
        }
    }
    @Test public void unrelatedDesiredChangesAndChangedNqnOrNsidStayRecoveryRequiredWithoutRestore() {
        JsonObject valid = manager.live.deepCopy();
        for (String field : List.of("nqn", "nsid", "otherFile", "access")) {
            manager.live = valid.deepCopy();JsonObject desired = manager.live.getAsJsonObject("configurationDesiredState");
            if (field.equals("nqn")) desired.getAsJsonObject("nvmeof-subsystems.json").getAsJsonArray("subsystems").get(0).getAsJsonObject().addProperty("targetName", "nqn.2026-10.local.storage:foreign");
            else if (field.equals("nsid")) namespace(desired).addProperty("lunOrNamespace", "2");
            else if (field.equals("access")) namespace(desired).getAsJsonObject("config").addProperty("readOnly", true);
            else desired.add("desired-state/nfs-export-apply.json", object("enabled", "true"));
            Assert.assertThrows(field, CloudRuntimeException.class, this::normalize);Assert.assertEquals(0, manager.restores);
        }
    }
    @Test public void staleProofForeignDataAndGenerationRaceBlockBeforeCanonicalRestore() {
        manager.inventory.addProperty("generatedEpoch", System.currentTimeMillis() / 1000.0 - 60);
        Assert.assertThrows(CloudRuntimeException.class, this::normalize);Assert.assertEquals(0, manager.restores);
        manager.inventory.addProperty("generatedEpoch", System.currentTimeMillis() / 1000.0);
        Mockito.when(manager.volume.getAccountId()).thenReturn(999L);
        Assert.assertThrows(CloudRuntimeException.class, this::normalize);Assert.assertEquals(0, manager.restores);
        Mockito.when(manager.volume.getAccountId()).thenReturn(2L);
        manager.statuses = 0;manager.race = true;
        Assert.assertThrows(CloudRuntimeException.class, this::normalize);Assert.assertEquals(0, manager.restores);
    }
    @Test public void missingPendingAndUnverifiedRestoreCannotClaimExactSourceNormalization() {
        manager.live.addProperty("pendingOperationUuid", UUID.randomUUID().toString());
        Assert.assertThrows(CloudRuntimeException.class, this::normalize);Assert.assertEquals(0, manager.restores);
        manager.live.addProperty("pendingOperationUuid", operation.getUuid());manager.wrongRestore = true;
        Assert.assertThrows(CloudRuntimeException.class, this::normalize);Assert.assertEquals(1, manager.restores);
    }
    private JsonObject desired(String state) {JsonObject file=new JsonObject();JsonArray acls=new JsonArray();JsonObject acl=new JsonObject();acl.addProperty("state",state);acl.addProperty("principal","same-user");acl.addProperty("permission","RW");acls.add(acl);file.add("acls",acls);JsonObject result=new JsonObject();result.add("desired-state/smb-share-apply.json",file);result.add("desired-state/nfs-export-apply.json",JsonNull.INSTANCE);return result;}
    @Test public void acceptedLifecycleStateHasSameAppliedMeaningWithoutWeakeningAclSemantics() {StorageCanonicalRecovery.requireSameMeaning(desired("Updating"),desired("Ready"));JsonObject different=desired("Ready");different.getAsJsonObject("desired-state/smb-share-apply.json").getAsJsonArray("acls").get(0).getAsJsonObject().addProperty("permission","RO");Assert.assertThrows(CloudRuntimeException.class,()->StorageCanonicalRecovery.requireSameMeaning(desired("Updating"),different));Assert.assertThrows(CloudRuntimeException.class,()->StorageCanonicalRecovery.requireSameMeaning(desired("Ready"),desired("Disabled")));}
    @Test public void absentFilesCannotBeSilentlyReplacedByEmptyDefaults() {JsonObject empty=desired("Ready");JsonObject nfs=new JsonObject();nfs.addProperty("enabled",true);nfs.add("exports",new JsonArray());empty.add("desired-state/nfs-export-apply.json",nfs);Assert.assertThrows(CloudRuntimeException.class,()->StorageCanonicalRecovery.requireSameMeaning(desired("Ready"),empty));Assert.assertTrue(StorageCanonicalRecovery.emptyProtocolFile(StorageServiceInstance.Protocol.NFS,nfs));nfs.getAsJsonArray("exports").add(new JsonObject());Assert.assertFalse(StorageCanonicalRecovery.emptyProtocolFile(StorageServiceInstance.Protocol.NFS,nfs));}
    @Test public void networkPrimaryAndBackingPathChangesRemainSemanticDifferences() {JsonObject source=desired("Ready");JsonObject actual=source.deepCopy();JsonObject network=new JsonObject();network.addProperty("primaryIp","10.10.13.240");source.add("network-endpoints.json",network);actual.add("network-endpoints.json",network.deepCopy());actual.getAsJsonObject("network-endpoints.json").addProperty("primaryIp","10.10.13.241");Assert.assertThrows(CloudRuntimeException.class,()->StorageCanonicalRecovery.requireSameMeaning(source,actual));}

    static JsonObject nfsSourceFixture() {
        String volume="5c53f9d6-4153-4e3d-a440-7d507f4a106b",fs="9eaf5fd5-28e3-413a-95d5-2856d656d7d4",export="5ba8fd61-5e04-4243-b9c3-47b837eccc1d";
        String root="/srv/ablestack-storage/volumes/"+volume,boot="0fce7e4a-7f46-4b33-9a6e-0137cdbcf8da";
        JsonObject config=object("attachedVolumeUuid",volume);config.addProperty("filesystemUuid",fs);config.addProperty("volumeMountPath",root);config.addProperty("backingPath",root+"/data");
        JsonObject inspection=new JsonObject();inspection.addProperty("sizeBytes",20L<<30);config.add("lastInspection",inspection);config.addProperty("endpointMode","LISTENER_GROUP");
        JsonArray ports=new JsonArray();ports.add(2049);config.add("listenerGroupPorts",ports);
        JsonObject share=object("uuid",export);share.addProperty("path","/export/source-nfs");share.addProperty("name","source-nfs");share.add("config",config);
        JsonObject acl=object("principal","10.10.13.1/32");acl.addProperty("permission","READ_WRITE");acl.add("config",new JsonObject());share.add("acls",array(acl));
        JsonObject nfs=new JsonObject();nfs.addProperty("enabled",true);nfs.addProperty("protocolMode","V4_ONLY");nfs.addProperty("port",2049);nfs.add("exports",array(share));
        JsonObject source=new JsonObject();for(String path:StorageRenderedDesiredState.PATHS)source.add(path,JsonNull.INSTANCE);source.add("desired-state/nfs-export-apply.json",nfs);
        JsonObject raw=object("volumeUuid",VOLUME);raw.addProperty("backstoreType","BLOCK");raw.addProperty("volumeSizeBytes",SIZE);
        JsonObject iscsi=new JsonObject();iscsi.add("targets",array(raw));source.add("iscsi-targets.json",iscsi);
        JsonObject endpoint=object("bootId",boot);endpoint.addProperty("listenIp","0.0.0.0");endpoint.addProperty("port",2049);endpoint.addProperty("pid",1845);endpoint.addProperty("startTicks","1046");
        endpoint.addProperty("protocolMode","V4_ONLY");endpoint.addProperty("configurationSha256","a".repeat(64));endpoint.addProperty("configurationTarget","/etc/ganesha/ablestack-storage/0.0.0.0_2049.conf");
        for(String key:List.of("processRunning","listening","listenerOwned","exportInventoryReady","probeObservationVerified","probeSuccess"))endpoint.addProperty(key,true);
        JsonObject probe=object("exportUuid",export);probe.addProperty("targetPath","/export/source-nfs");probe.addProperty("pseudoPath","/source-nfs");probe.addProperty("success",true);endpoint.add("probeResults",array(probe));
        JsonObject nfsHealth=new JsonObject();nfsHealth.add("endpoints",array(endpoint));JsonObject health=object("status","ok");health.addProperty("success",true);health.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);health.add("nfsGanesha",nfsHealth);
        JsonObject entry=object("path","/export/source-nfs");entry.addProperty("pseudo","/source-nfs");JsonArray clients=new JsonArray();clients.add(StorageRecoveryObservation.nfsClient("10.10.13.1/32",true,config,new JsonObject()));entry.add("clients",clients);
        JsonObject listener=object("listenIp","0.0.0.0");listener.addProperty("port",2049);listener.addProperty("pid",1845);listener.addProperty("status","active");listener.addProperty("processRunning",true);listener.addProperty("listening",true);listener.add("entries",array(entry));
        JsonObject inventory=object("status","ok");inventory.addProperty("success",true);inventory.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);inventory.add("nfsGaneshaExports",array(listener));
        JsonObject fileData=object("volumeUuid",volume);fileData.addProperty("kind","FILE_DATA");fileData.addProperty("mappingStatus","EXACT");fileData.addProperty("matchedBy","VOLUME_SERIAL");fileData.addProperty("serial",volume.replace("-","").substring(0,20));fileData.addProperty("sizeBytes",20L<<30);fileData.addProperty("filesystemUuid",fs);fileData.addProperty("filesystem","ext4");
        JsonObject mount=object("target","/export/source-nfs");mount.addProperty("source","/dev/sdd[/data]");mount.addProperty("uuid",fs);fileData.add("mounts",array(mount));
        JsonObject rawData=object("volumeUuid",VOLUME);rawData.addProperty("kind","BLOCK_RAW");rawData.addProperty("mappingStatus","EXACT");rawData.addProperty("matchedBy","VOLUME_SERIAL");rawData.addProperty("serial",VOLUME.replace("-","").substring(0,20));rawData.addProperty("sizeBytes",SIZE);
        JsonArray volumes=array(fileData);volumes.add(rawData);JsonObject data=object("bootId",boot);data.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);data.add("volumes",volumes);
        JsonObject result=new JsonObject();result.add("source",source);result.add("health",health);result.add("inventory",inventory);result.add("data",data);return result;
    }
    static JsonObject nfsProof(JsonObject f) {
        StorageCanonicalRecovery.requireSourceData(f.getAsJsonObject("source"),f.getAsJsonObject("data"));
        return StorageCanonicalRecovery.requireNfsSourceRuntime(f.getAsJsonObject("source").getAsJsonObject("desired-state/nfs-export-apply.json"),
                f.getAsJsonObject("health").getAsJsonObject("nfsGanesha"),f.getAsJsonObject("inventory"),f.getAsJsonObject("data"),f.getAsJsonObject("data").get("bootId").getAsString());
    }
    @Test public void sourceNfsBindsFreshConfigProcessExportsAclAliasAndFileRawIdentities() {
        JsonObject proof=nfsProof(nfsSourceFixture());Assert.assertEquals(1,proof.size());Assert.assertEquals(1845,proof.getAsJsonObject("0.0.0.0:2049").get("pid").getAsInt());
    }
    @Test public void targetAclForeignAliasExtraExportAndUnverifiedProcessCannotSkipSourceNfs() {
        for(String change:List.of("acl","alias","extraExport","pid","configPath","unverified","stringFlag")) {
            JsonObject f=nfsSourceFixture(),endpoint=f.getAsJsonObject("health").getAsJsonObject("nfsGanesha").getAsJsonArray("endpoints").get(0).getAsJsonObject();
            JsonObject listener=f.getAsJsonObject("inventory").getAsJsonArray("nfsGaneshaExports").get(0).getAsJsonObject();
            if(change.equals("acl"))listener.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonArray("clients").get(0).getAsJsonObject().addProperty("access","RO");
            else if(change.equals("alias"))f.getAsJsonObject("data").getAsJsonArray("volumes").get(0).getAsJsonObject().getAsJsonArray("mounts").get(0).getAsJsonObject().addProperty("source","/dev/sdd[/foreign]");
            else if(change.equals("extraExport"))listener.getAsJsonArray("entries").add(listener.getAsJsonArray("entries").get(0).deepCopy());
            else if(change.equals("pid"))listener.addProperty("pid",999);
            else if(change.equals("configPath"))endpoint.addProperty("configurationTarget","/tmp/foreign.conf");
            else if(change.equals("unverified"))endpoint.addProperty("probeObservationVerified",false);
            else endpoint.addProperty("listenerOwned","true");
            Assert.assertThrows(change,RuntimeException.class,()->nfsProof(f));
        }
    }
    @Test public void wrongRawSerialSizeFileFilesystemAndMissingDataCannotAuthorizeSourceNfsSkip() {
        for(String change:List.of("serial","size","filesystem","missing")) {
            JsonObject f=nfsSourceFixture();JsonArray volumes=f.getAsJsonObject("data").getAsJsonArray("volumes");
            if(change.equals("serial"))volumes.get(1).getAsJsonObject().addProperty("serial","foreignserial");
            else if(change.equals("size"))volumes.get(1).getAsJsonObject().addProperty("sizeBytes",SIZE+1);
            else if(change.equals("filesystem"))volumes.get(0).getAsJsonObject().addProperty("filesystemUuid",UUID.randomUUID().toString());
            else volumes.remove(1);
            Assert.assertThrows(change,RuntimeException.class,()->nfsProof(f));
        }
    }
}

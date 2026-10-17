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
}

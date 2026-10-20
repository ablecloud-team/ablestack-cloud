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

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.google.gson.*;
import java.util.*;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/** Replay uses the original protected file, including primary/null representation, not a newly selected DAO row. */
public class StorageIscsiAclSourceRollbackTest {
    static final String INSTANCE="11111111-1111-4111-8111-111111111111",OP="22222222-2222-4222-8222-222222222222",
            SOURCE="33333333-3333-4333-8333-333333333333",BOOT="44444444-4444-4444-8444-444444444444";
    static class Manager extends StorageServiceManagerImpl {
        JsonObject database,observed;int probes;String sent;
        @Override protected String captureConfigurationSnapshot(long id){return database.toString();}
        @Override protected void requireConfigurationAdministrator() { }
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action){probes++;return observed.deepCopy();}
        @Override protected StorageServiceGuestCommandResult dispatchPlainProtocolApply(StorageServiceInstanceVO instance,StorageServiceGuestCommand command) {
            sent=command.getPayload();return new StorageServiceGuestCommandResult(true,"verified","{}");
        }
    }
    static class Fixture implements AutoCloseable {
        final Manager manager=new Manager();final StorageServiceInstanceVO instance=new StorageServiceInstanceVO();
        final StorageServiceOperationVO operation=new StorageServiceOperationVO();
        final JsonObject before=new JsonObject(),frozen,snapshot,context,iscsi=new JsonObject();
        Fixture() {this("EXPLICIT");}
        Fixture(String primary) {
            User user=Mockito.mock(User.class);Account account=Mockito.mock(Account.class);Mockito.when(user.getId()).thenReturn(2L);Mockito.when(account.getId()).thenReturn(2L);
            org.apache.cloudstack.context.CallContext.register(user,account);
            ReflectionTestUtils.setField(instance,"id",7L);ReflectionTestUtils.setField(instance,"uuid",INSTANCE);instance.setVmId(42L);
            ReflectionTestUtils.setField(operation,"id",9L);ReflectionTestUtils.setField(operation,"uuid",OP);
            operation.setInstanceId(7);operation.setCreatedBy(2);operation.setRevision(9);operation.setAction("createstorageiscsiaclresponse");
            operation.setState("RUNNING");operation.setPhase("ROLLING_BACK");operation.setProgress(85);
            JsonObject writer=new JsonObject();writer.addProperty("instanceUuid",INSTANCE);writer.addProperty("operationUuid",OP);writer.addProperty("revision",9);
            JsonObject generation=new JsonObject();generation.addProperty("instanceUuid",INSTANCE);generation.addProperty("operationUuid",SOURCE);generation.addProperty("revision",8);generation.addProperty("configurationSha256","a".repeat(64));
            iscsi.addProperty("instanceUuid",INSTANCE);iscsi.addProperty("instanceId",7);iscsi.addProperty("enabled",true);iscsi.addProperty("port",3260);
            if("EXPLICIT".equals(primary))iscsi.addProperty("listenIp","192.0.2.10");else if("NULL".equals(primary))iscsi.add("listenIp",JsonNull.INSTANCE);
            JsonArray listeners=new JsonArray();JsonObject wildcard=new JsonObject();wildcard.addProperty("listenIp","0.0.0.0");wildcard.addProperty("port",3260);listeners.add(wildcard);iscsi.add("listeners",listeners);
            iscsi.add("endpointAliases",new JsonArray());iscsi.add("targets",new JsonArray());
            JsonObject files=new JsonObject();for(String path:StorageRenderedDesiredState.PATHS)files.add(path,JsonNull.INSTANCE);files.add("iscsi-targets.json",iscsi);
            before.addProperty("success",true);before.addProperty("generationSupported",true);before.addProperty("generationStatus","IN_SYNC");before.add("pendingOperationUuid",JsonNull.INSTANCE);
            before.addProperty("bootId",BOOT);before.add("generation",generation);before.addProperty("configurationSha256","a".repeat(64));before.add("configurationDesiredState",files);
            context=StorageLocalSourceIdentityProof.context(writer,before);
            manager.database=new JsonObject();manager.database.addProperty("schemaVersion",1);manager.database.addProperty("instanceId",7);manager.database.add("tables",new JsonArray());
            snapshot=manager.database.deepCopy();snapshot.add("nativeDesiredState",files.deepCopy());JsonObject nativeGeneration=new JsonObject();nativeGeneration.add("previous",generation.deepCopy());snapshot.add("nativeGeneration",nativeGeneration);
            JsonObject checkpoint=new JsonObject();checkpoint.addProperty("kind","LOCAL_SOURCE_IDENTITY_CHECKPOINT");checkpoint.add("scope",StorageLocalSourceIdentityProof.scope(context));
            checkpoint.addProperty("capsuleSha256","c".repeat(64));checkpoint.addProperty("sourceConfigurationSha256","a".repeat(64));checkpoint.addProperty("checkpointRecordSha256","d".repeat(64));
            JsonObject reference=new JsonObject();reference.addProperty("operationUuid",OP);reference.add("sourceLocalScope",StorageLocalSourceIdentityProof.scope(context));reference.add("sourceLocalIdentityCheckpoint",checkpoint);snapshot.add("nativeIdentityCapsule",reference);
            JsonObject intent=new JsonObject();intent.add("context",context);intent.add("source",before.deepCopy());intent.addProperty("sourceProtocol","ISCSI");
            JsonArray domains=new JsonArray();domains.add("ISCSI");intent.add("authReplayDomains",domains);
            JsonObject restored=new JsonObject();restored.addProperty("success",true);restored.add("scope",StorageLocalSourceIdentityProof.scope(context));
            restored.addProperty("sourceSmbResumed",true);restored.addProperty("sourceRuntimeVerified",true);restored.addProperty("canonicalDesiredStateChanged",false);restored.addProperty("sourceIdentityRestored",true);intent.add("restored",restored);
            JsonObject auth=new JsonObject();auth.addProperty("success",true);auth.add("scope",StorageLocalSourceIdentityProof.scope(context));auth.addProperty("sourceAuthReplayed",true);
            auth.addProperty("canonicalDesiredStateChanged",false);auth.addProperty("smbIdentityChanged",false);auth.add("replayedDomains",domains.deepCopy());
            auth.addProperty("sourceConfigurationSha256","a".repeat(64));auth.add("localSourceCheckpointReference",StorageLocalSourceIdentityProof.reference(checkpoint));intent.add("authReplayed",auth);
            snapshot.add("localCheckpointIntent",intent);publish();
            frozen=before.deepCopy();frozen.addProperty("frozen",true);
            manager.observed=before.deepCopy();manager.observed.addProperty("generationStatus","PENDING");manager.observed.addProperty("pendingOperationUuid",OP);
            ((ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(manager,"storageWriterOperation")).set(operation);
            ((ThreadLocal<JsonObject>)ReflectionTestUtils.getField(manager,"configurationRecoverySource")).set(frozen);
        }
        void publish(){operation.setPreviousSnapshotJson(snapshot.toString());}
        JsonObject payload(){return manager.buildIscsiDesiredPayload(instance,Collections.emptyMap());}
        @Override public void close(){((ThreadLocal<?>)ReflectionTestUtils.getField(manager,"storageWriterOperation")).remove();((ThreadLocal<?>)ReflectionTestUtils.getField(manager,"configurationRecoverySource")).remove();org.apache.cloudstack.context.CallContext.unregister();}
    }
    @Test public void allOrdinaryAclActionsReplayTheirOriginalSourceWithoutRepresentativeDaoAccess() {
        for(String action:List.of("createstorageiscsiaclresponse","updatestorageiscsiaclresponse","deletestorageiscsiaclresponse")) {
            try(Fixture f=new Fixture()) {
                f.operation.setAction(action);JsonObject original=f.frozen.getAsJsonObject("configurationDesiredState").getAsJsonObject("iscsi-targets.json");
                JsonObject replay=f.payload();Assert.assertEquals(original,replay);Assert.assertEquals("192.0.2.10",replay.get("listenIp").getAsString());
                replay.addProperty("listenIp","changed-return-copy");Assert.assertEquals("192.0.2.10",original.get("listenIp").getAsString());
                Assert.assertEquals(1,f.manager.probes);
            }
        }
    }
    @Test public void nullAndAbsentSourcePrimaryRemainExactInTheActualApplyWire() {
        for(String primary:List.of("EXPLICIT","NULL","ABSENT"))try(Fixture f=new Fixture(primary)) {
            JsonObject original=f.frozen.getAsJsonObject("configurationDesiredState").getAsJsonObject("iscsi-targets.json");
            f.manager.applyIscsiDesiredState(f.instance);
            JsonObject sent=JsonParser.parseString(f.manager.sent).getAsJsonObject();Assert.assertEquals(primary,original,sent);
            Assert.assertEquals(original.has("listenIp"),sent.has("listenIp"));
            if("NULL".equals(primary))Assert.assertTrue(sent.get("listenIp").isJsonNull());
        }
    }
    @Test public void foreignActorWrongPhaseChangedDatabaseOrBootCannotBorrowOriginalRollbackAuthority() {
        try(Fixture f=new Fixture()){f.operation.setCreatedBy(99);Assert.assertThrows(CloudRuntimeException.class,f::payload);}
        try(Fixture f=new Fixture()){f.operation.setPhase("APPLYING");Assert.assertThrows(CloudRuntimeException.class,f::payload);}
        try(Fixture f=new Fixture()){f.manager.database.addProperty("instanceId",8);Assert.assertThrows(CloudRuntimeException.class,f::payload);}
        try(Fixture f=new Fixture()){f.manager.observed.addProperty("bootId",SOURCE);Assert.assertThrows(CloudRuntimeException.class,f::payload);}
        try(Fixture f=new Fixture()){f.manager.observed.addProperty("pendingOperationUuid",SOURCE);Assert.assertThrows(CloudRuntimeException.class,f::payload);}
    }
    @Test public void missingOpaqueImportOrChangedAuthDomainsAreRejectedWithoutNewSecretReplay() {
        try(Fixture f=new Fixture()){f.snapshot.getAsJsonObject("localCheckpointIntent").getAsJsonObject("restored").addProperty("sourceIdentityRestored",false);f.publish();Assert.assertThrows(CloudRuntimeException.class,f::payload);}
        try(Fixture f=new Fixture()){f.snapshot.getAsJsonObject("localCheckpointIntent").getAsJsonArray("authReplayDomains").add("NVMEOF");f.publish();Assert.assertThrows(CloudRuntimeException.class,f::payload);}
        try(Fixture f=new Fixture()){JsonObject injected=new JsonObject();injected.addProperty("chapSecret","synthetic-not-allowed");Assert.assertThrows(CloudRuntimeException.class,()->f.manager.buildIscsiDesiredPayload(f.instance,Map.of(1L,injected)));}
    }
    @Test public void explicitOriginalAdministratorRevalidationUsesOnlyItsOwnSourceAndIscsiReplay() {
        try(Fixture f=new Fixture()) {
            f.operation.setState("RECOVERY_REQUIRED");f.operation.setPhase("RECOVERING_INTERRUPTED_WRITER");
            Assert.assertThrows(CloudRuntimeException.class,f::payload);
            ((ThreadLocal<Long>)ReflectionTestUtils.getField(f.manager,"explicitUnchangedSourceRecovery")).set(f.operation.getId());
            try {
                Assert.assertEquals(f.frozen.getAsJsonObject("configurationDesiredState").get("iscsi-targets.json"),f.payload());
                Assert.assertNull(f.manager.applyRestoredSourceProtocols(f.instance,f.operation));
                Assert.assertNotNull(f.manager.sent);Assert.assertEquals(2,f.manager.probes);
            } finally {((ThreadLocal<?>)ReflectionTestUtils.getField(f.manager,"explicitUnchangedSourceRecovery")).remove();}
        }
        try(Fixture f=new Fixture()) {
            f.operation.setState("RECOVERY_REQUIRED");f.operation.setPhase("RECOVERING_INTERRUPTED_WRITER");
            ((ThreadLocal<Long>)ReflectionTestUtils.getField(f.manager,"explicitUnchangedSourceRecovery")).set(999L);
            try{Assert.assertThrows(CloudRuntimeException.class,f::payload);}finally{((ThreadLocal<?>)ReflectionTestUtils.getField(f.manager,"explicitUnchangedSourceRecovery")).remove();}
        }
    }
    @Test public void foreignFrozenPayloadCannotBorrowSameActorAndGenerationProof() {
        try(Fixture f=new Fixture()) {
            f.frozen.getAsJsonObject("configurationDesiredState").getAsJsonObject("iscsi-targets.json").addProperty("instanceId",8);
            f.snapshot.getAsJsonObject("localCheckpointIntent").getAsJsonObject("source").getAsJsonObject("configurationDesiredState")
                    .getAsJsonObject("iscsi-targets.json").addProperty("instanceId",8);
            f.manager.observed.getAsJsonObject("configurationDesiredState").getAsJsonObject("iscsi-targets.json").addProperty("instanceId",8);
            f.publish();Assert.assertThrows(CloudRuntimeException.class,f::payload);
        }
    }
    @Test public void restoreAndServiceWritersKeepTheirExistingSeparateRecoveryPolicies() {
        for(String action:List.of("applystorageserviceconfigrestoreresponse","restorestorageservicelastknowngoodresponse","SERVICE_MAINTENANCE")) {
            try(Fixture f=new Fixture()){f.operation.setAction(action);Assert.assertNull(f.manager.ordinaryIscsiAclRollbackPayload(f.instance,Collections.emptyMap()));Assert.assertEquals(0,f.manager.probes);}
        }
    }
}

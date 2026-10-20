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

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import org.junit.Test;
import org.junit.Assert;
import org.junit.Before;
import org.junit.After;
import org.mockito.Mockito;

/** Begins with a durable creator/foundation receipt; exercises the complete restore controller thereafter. */
public class StorageConfigRestoreWholeFlowTest {
    private Fixture fixture;
    @Before public void setup()throws Exception {fixture=new Fixture();fixture.setup();}
    @After public void close()throws Exception {if(fixture!=null)fixture.close();}

    @Test public void retainedDataRestoresSixteenResourcesWithRealGenerationAndRenderedEngines()throws Exception {
        requireCompletedRestore();
    }

    private void primaryMappedFixture()throws Exception {
        fixture.close();fixture=new Fixture();fixture.primaryNfsFourProtocols=true;fixture.setup();
    }

    @Test public void initialNfsDriftAfterMaintenanceCannotBeAdoptedAndRestoresTheSameSource()throws Exception {
        primaryMappedFixture();String disks=new com.google.gson.Gson().toJson(fixture.volumeRows);
        ((BuildManifestManager)fixture.manager).beforeInitialNfsReuse=()->{
            StorageServiceProtocolVO row=(StorageServiceProtocolVO)fixture.database.rows.get("storage_service_protocol").values().iterator().next();
            row.setConfigJson("{\"changed\":true}");
        };
        com.cloud.utils.exception.CloudRuntimeException error=Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
            ()->fixture.manager.storageServiceConfiguration(fixture.reviewedCommand()));
        Assert.assertTrue(error.getMessage(),error.getMessage().contains("Initial NFS reuse baseline database changed"));
        Assert.assertEquals(fixture.initialDatabaseSnapshot,fixture.database.snapshot(80L));
        Assert.assertEquals(disks,new com.google.gson.Gson().toJson(fixture.volumeRows));
        Assert.assertFalse(fixture.nativeActions().contains("render-stage"));
        JsonObject current=fixture.generation("operation generation status",null);
        Assert.assertEquals("IN_SYNC",current.get("generationStatus").getAsString());Assert.assertTrue(current.get("pendingOperationUuid").isJsonNull());
        Assert.assertEquals(1,current.get("runtimeRevision").getAsLong());
        Assert.assertEquals(1,fixture.artifactRows.values().stream().filter(v->"ACTIVE_LKG".equals(v.getState())&&v.getDesiredRevision()==1).count());
    }

    @Test public void primaryMappedFourProtocolRestoreReusesOnlyItsInitialNfsEndpoint()throws Exception {
        primaryMappedFixture();
        String initial=fixture.database.rows.get("storage_service_protocol").values().stream()
            .map(v->((StorageServiceProtocolVO)v).getUuid()).findFirst().orElseThrow();
        requireCompletedRestore();
        java.util.List<StorageServiceProtocolVO> nfs=fixture.database.rows.get("storage_service_protocol").values().stream()
            .map(v->(StorageServiceProtocolVO)v).filter(v->v.getProtocol()==StorageServiceInstance.Protocol.NFS).collect(java.util.stream.Collectors.toList());
        Assert.assertEquals(1,nfs.size());Assert.assertEquals(initial,nfs.get(0).getUuid());
        Assert.assertEquals("10.10.13.226",nfs.get(0).getListenIp());Assert.assertEquals(Integer.valueOf(2049),nfs.get(0).getPort());
        Assert.assertEquals(4,fixture.database.rows.get("storage_service_protocol").size());
    }

    @Test public void approvedExistingRestoreRollsBackTheSameDefaultThenCompletesThroughTheRealController()throws Exception {
        fixture.manager.prepareConfigurationCloneInitialBaseline(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        fixture.manager.authorizeConfigurationCloneProfile(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        String creator=fixture.artifactRows.get(900L).getMetadataJson(),disks=new com.google.gson.Gson().toJson(fixture.volumeRows);
        JsonObject source=fixture.generation("operation generation status",null),identity=fixture.localPublicFingerprint(),lkg=fixture.initialLkgIdentity();
        fixture.rejectStageOnce=true;
        var failedCommand=fixture.existingApprovedCommand("approved-existing-first");Assert.assertEquals("APPLY",failedCommand.getConfigAction());
        var stageFailure=Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->fixture.manager.applyStorageServiceApprovedMaintenance(failedCommand));
        if(fixture.rejectStageOnce)throw new AssertionError("The actual render-stage fault was not reached",stageFailure);
        Assert.assertEquals("Fixture transport rejected stage before activation",stageFailure.getMessage());
        StorageServiceOperationVO failed=fixture.operationRows.values().stream().filter(v->"SERVICE_MAINTENANCE".equals(v.getAction())).findFirst().orElseThrow();
        Assert.assertEquals("RECOVERY_REQUIRED",failed.getState());
        JsonObject recovery=new JsonObject();recovery.addProperty("operationid",failed.getId());
        var reconcile=(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd)StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd.class,recovery);
        // Historical deployed writers did not yet store the later Java held-reply marker.
        JsonObject historical=fixture.obj(failed.getPreviousSnapshotJson());historical.remove("approvedServiceMaintenanceHeld");failed.setPreviousSnapshotJson(historical.toString());
        fixture.loseHistoricalHeldTerminalOnce=true;
        var terminal=Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->fixture.manager.reconcileStorageServiceOperation(reconcile));
        if(fixture.loseHistoricalHeldTerminalOnce || !"Rendered rollback terminal could not be persisted".equals(terminal.getMessage()))throw terminal;
        StorageServiceOperationVO released=fixture.operationRows.get(failed.getId());Assert.assertEquals("RECOVERY_REQUIRED",released.getState());Assert.assertEquals(100,released.getProgress());
        Assert.assertFalse(fixture.obj(released.getPreviousSnapshotJson()).has("approvedServiceMaintenanceHeld"));
        JsonObject scope=new JsonObject();scope.addProperty("instanceUuid",fixture.TARGET);scope.addProperty("operationUuid",failed.getUuid());scope.addProperty("revision",failed.getRevision());scope.addProperty("maintenanceUuid",failed.getUuid());
        Assert.assertFalse(fixture.generation("operation maintenance service-status",scope).get("bootHeld").getAsBoolean());
        Assert.assertTrue(fixture.generation("operation generation status",null).get("pendingOperationUuid").isJsonNull());
        Assert.assertFalse(fixture.manager.recoverInitialUnheldServiceSource(fixture.instanceRows.get(80L),released));
        String restoredDatabase=fixture.database.snapshot(80L);java.util.List<String> beforeFinish=fixture.nativeActions();
        ((StorageServiceProtocolVO)fixture.database.rows.get("storage_service_protocol").values().iterator().next()).setPort(2050);
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->fixture.manager.reconcileStorageServiceOperation(reconcile));fixture.database.restore(80L,restoredDatabase);
        java.nio.file.Path releasePath=fixture.nativeCase.resolve("maintenance/template-maintenance-release.json");String priorRelease=Files.readString(releasePath);
        JsonObject foreign=fixture.obj(priorRelease);foreign.getAsJsonObject("scope").addProperty("operationUuid",fixture.id(999));Files.writeString(releasePath,foreign.toString());
        try {var missing=Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->fixture.manager.reconcileStorageServiceOperation(reconcile));Assert.assertEquals("ROOT runtime step failed: operation maintenance service-release",missing.getMessage());}
        finally {Files.writeString(releasePath,priorRelease);}
        fixture.loseReleasedInitialFinalizationOnce=true;
        var finalSave=Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->fixture.manager.reconcileStorageServiceOperation(reconcile));
        if(fixture.loseReleasedInitialFinalizationOnce || !"Resource-finalized operation completion could not be persisted".equals(finalSave.getMessage()))throw finalSave;
        fixture.manager.reconcileStorageServiceOperation(reconcile);
        Assert.assertEquals(priorRelease,Files.readString(releasePath));
        for(String action:java.util.List.of("begin","rollback","render-maintenance-resume-source","render-stage","render-activate"))
            Assert.assertEquals(action,beforeFinish.stream().filter(action::equals).count(),fixture.nativeActions().stream().filter(action::equals).count());
        Assert.assertEquals("ROLLED_BACK",fixture.operationRows.get(failed.getId()).getState());
        Assert.assertEquals(source.get("generation"),fixture.generation("operation generation status",null).get("generation"));
        Assert.assertEquals(fixture.initialDatabaseSnapshot,fixture.database.snapshot(80L));Assert.assertEquals(identity,fixture.localPublicFingerprint());
        Assert.assertEquals(lkg,fixture.initialLkgIdentity());Assert.assertEquals(disks,new com.google.gson.Gson().toJson(fixture.volumeRows));fixture.assertEmptySourceRuntime();
        String failedHistory=new com.google.gson.Gson().toJson(fixture.operationRows.get(failed.getId()));
        var retry=fixture.existingApprovedCommand("approved-existing-after-rollback");
        JsonObject retryMetadata=fixture.obj(fixture.artifactRows.get(901L).getMetadataJson());
        Assert.assertEquals("FAILED",retryMetadata.get("restoreState").getAsString());
        Assert.assertEquals("CONFIG_RESTORE_FAILED",retryMetadata.get("errorCode").getAsString());
        var response=fixture.manager.applyStorageServiceApprovedMaintenance(retry);
        JsonObject runtime=fixture.obj(new com.google.gson.Gson().toJson(response));
        Assert.assertTrue(runtime.get("success").getAsBoolean());Assert.assertEquals("COMPLETE",runtime.get("status").getAsString());
        JsonObject artifactResponse=fixture.obj(runtime.get("resultjson").getAsString());
        assertCompletedArtifactResponse(artifactResponse);
        Assert.assertEquals(failedHistory,new com.google.gson.Gson().toJson(fixture.operationRows.get(failed.getId())));
        assertCompletedRestore(disks,901L);Assert.assertEquals(creator,fixture.artifactRows.get(900L).getMetadataJson());
        StorageConfigRequest imports=Mockito.mock(StorageConfigRequest.class);
        Mockito.when(imports.getConfigAction()).thenReturn("IMPORTS");Mockito.when(imports.getInstanceId()).thenReturn(80L);
        JsonObject listed=fixture.obj(new com.google.gson.Gson().toJson(fixture.configuration.execute(imports)));
        JsonArray rows=fixture.obj(listed.get("result").getAsString()).getAsJsonArray("artifacts");
        JsonObject imported=java.util.stream.StreamSupport.stream(rows.spliterator(),false).map(JsonElement::getAsJsonObject)
                .filter(row->fixture.artifactRows.get(901L).getUuid().equals(row.get("id").getAsString())).findFirst().orElseThrow();
        Assert.assertEquals("COMPLETE",imported.getAsJsonObject("metadata").get("restoreState").getAsString());
        Assert.assertFalse(imported.getAsJsonObject("metadata").has("errorCode"));
        Assert.assertTrue(fixture.operationRows.values().stream().anyMatch(v->"SERVICE_MAINTENANCE".equals(v.getAction())&&"COMPLETE".equals(v.getState())));
        Assert.assertEquals(3,fixture.nativeActions().stream().filter("begin"::equals).count());
    }

    @Test public void nfsOnlySourceWithoutSamCompletesPrestopCipherAndSecondRestoreWithoutCreatingSambaIdentity()throws Exception {
        primaryMappedFixture();fixture.manager.prepareConfigurationCloneInitialBaseline(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        fixture.manager.authorizeConfigurationCloneProfile(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        fixture.installNfsOnlyImport();fixture.manager.applyStorageServiceApprovedMaintenance(fixture.existingApprovedCommand("nfs-only-first"));
        JsonObject source=fixture.generation("operation generation status",null);
        Assert.assertTrue(source.getAsJsonObject("configurationDesiredState").get(StorageRenderedDesiredState.PROTOCOL_PATHS.get(StorageServiceInstance.Protocol.SMB)).isJsonNull());
        Assert.assertFalse(Files.exists(fixture.nativeCase.resolve("rendered-os-fixture/var/lib/samba/private/secrets.tdb")));
        var response=fixture.manager.applyStorageServiceApprovedMaintenance(fixture.activeApprovedCommand("nfs-only-second"));
        Assert.assertTrue(fixture.obj(new com.google.gson.Gson().toJson(response)).get("success").getAsBoolean());
        JsonObject current=fixture.generation("operation generation status",null);Assert.assertEquals(3,current.get("runtimeRevision").getAsLong());
        StorageServiceOperationVO operation=fixture.operationRows.values().stream().filter(row->"SERVICE_MAINTENANCE".equals(row.getAction())&&row.getRevision()==3).findFirst().orElseThrow();
        JsonObject snapshot=fixture.obj(operation.getPreviousSnapshotJson()),captured=snapshot.getAsJsonObject("adServiceSource"),stopped=snapshot.getAsJsonObject("adServiceStopped");
        Assert.assertTrue(captured.get("publicLocalMachineSid").isJsonNull());Assert.assertTrue(captured.get("localSamAbsentSha256").getAsString().matches("[a-f0-9]{64}"));
        Assert.assertEquals(captured.get("localSamAbsentSha256"),stopped.get("localSamAbsentSha256"));
        Assert.assertTrue(stopped.get("serviceSourceStoppedVerified").getAsBoolean());Assert.assertTrue(snapshot.has("adServiceCipherCheckpoint"));
        Assert.assertFalse(Files.exists(fixture.nativeCase.resolve("rendered-os-fixture/var/lib/samba/private/secrets.tdb")));
        Assert.assertFalse(Files.exists(fixture.nativeCase.resolve("rendered-os-fixture/var/lib/samba/private/passdb.tdb")));
        Assert.assertEquals("COMPLETE",operation.getState());
        JsonObject observed=fixture.generation("operation observe",new JsonObject());
        for(String domain:java.util.List.of("NFS","SMB","ISCSI","NVMEOF"))Assert.assertTrue(observed.getAsJsonObject("realRenderedProtocolVerification").get(domain).getAsBoolean());
    }

    @Test public void activeFourProtocolSourceRestoresAgainThroughProtectedPrestopStoppedCipherAndKeptInputs()throws Exception {
        primaryMappedFixture();
        fixture.manager.prepareConfigurationCloneInitialBaseline(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        fixture.manager.authorizeConfigurationCloneProfile(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        fixture.manager.applyStorageServiceApprovedMaintenance(fixture.existingApprovedCommand("active-source-first"));
        String disks=new com.google.gson.Gson().toJson(fixture.volumeRows);
        JsonObject source=fixture.generation("operation generation status",null);
        for(String domain:java.util.List.of("NFS","SMB","ISCSI","NVME_OF"))
            Assert.assertFalse(source.getAsJsonObject("configurationDesiredState").get(StorageRenderedDesiredState.PROTOCOL_PATHS.get(StorageServiceInstance.Protocol.valueOf(domain))).isJsonNull());
        for(int expectedRevision=3;expectedRevision<=4;expectedRevision++) {
            var command=fixture.activeApprovedCommand("active-source-repeat-"+expectedRevision);
            JsonObject plan=fixture.obj(fixture.artifactRows.get(901L).getMetadataJson()).getAsJsonObject("plan");
            Assert.assertEquals(0,plan.getAsJsonArray("create").size());Assert.assertEquals(0,plan.getAsJsonArray("preserve").size());
            Assert.assertTrue("Actual KEEP credentials must pass through the active-source controller",plan.getAsJsonArray("keep").size()>=10);
            JsonObject reply=fixture.obj(new com.google.gson.Gson().toJson(fixture.manager.applyStorageServiceApprovedMaintenance(command)));
            Assert.assertTrue(reply.get("success").getAsBoolean());Assert.assertEquals("COMPLETE",reply.get("status").getAsString());
            JsonObject current=fixture.generation("operation generation status",null);
            Assert.assertEquals(expectedRevision,current.get("runtimeRevision").getAsLong());Assert.assertEquals("IN_SYNC",current.get("generationStatus").getAsString());
            Assert.assertTrue(current.get("pendingOperationUuid").isJsonNull());
            StorageServiceOperationVO operation=fixture.operationRows.values().stream()
                .filter(row->"SERVICE_MAINTENANCE".equals(row.getAction())&&row.getRevision()==current.get("runtimeRevision").getAsLong()).findFirst().orElseThrow();
            JsonObject snapshot=fixture.obj(operation.getPreviousSnapshotJson());
            Assert.assertEquals(source.get("generation"),snapshot.getAsJsonObject("adServiceSource").get("sourceGeneration"));
            Assert.assertTrue(snapshot.getAsJsonObject("adServiceStopped").get("serviceSourceStoppedVerified").getAsBoolean());
            Assert.assertEquals(snapshot.get("adServiceStopped"),snapshot.get("approvedServiceMaintenanceHeld"));
            Assert.assertEquals(snapshot.get("adServiceCipherCheckpoint"),snapshot.getAsJsonObject("nativeIdentityCapsule").get("sourceServiceIdentityCheckpoint"));
            Assert.assertTrue(snapshot.getAsJsonObject("renderedGeneration").has("targetInputRef"));
            Assert.assertFalse(operation.getPreviousSnapshotJson().contains("fixture-secret"));
            Assert.assertEquals(disks,new com.google.gson.Gson().toJson(fixture.volumeRows));source=current;
        }
        Assert.assertEquals(2,fixture.nativeActions().stream().filter("render-service-capture-source"::equals).count());
    }

    @Test public void activeSourceEnterFenceFailureExplicitlyAbortsOnlyItsOwnPendingWithoutRestartingServices()throws Exception {
        primaryMappedFixture();fixture.manager.prepareConfigurationCloneInitialBaseline(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        fixture.manager.authorizeConfigurationCloneProfile(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        fixture.manager.applyStorageServiceApprovedMaintenance(fixture.existingApprovedCommand("active-source-before-fence"));
        JsonObject original=fixture.generation("operation generation status",null),identity=fixture.localPublicFingerprint();String database=fixture.database.snapshot(80L);
        var command=fixture.activeApprovedCommand("active-source-enter-fence");fixture.rejectServiceEnterBeforeHoldOnce=true;
        Assert.assertEquals("Fixture rejected formatter lock fence before SERVICE hold",Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
            ()->fixture.manager.applyStorageServiceApprovedMaintenance(command)).getMessage());
        StorageServiceOperationVO failed=fixture.operationRows.values().stream().filter(row->"SERVICE_MAINTENANCE".equals(row.getAction())&&row.getRevision()==3).findFirst().orElseThrow();
        Assert.assertFalse(fixture.obj(failed.getPreviousSnapshotJson()).has("approvedServiceMaintenanceHeld"));
        Assert.assertTrue(fixture.obj(failed.getPreviousSnapshotJson()).has("adServiceSource"));
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->fixture.manager.recoverRenderedStorageWriter(fixture.instanceRows.get(80L),failed));
        JsonObject args=new JsonObject();args.addProperty("operationid",failed.getId());
        var reconcile=(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd)StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd.class,args);
        fixture.manager.reconcileStorageServiceOperation(reconcile);
        Assert.assertEquals("ROLLED_BACK",fixture.operationRows.get(failed.getId()).getState());
        JsonObject after=fixture.generation("operation generation status",null);Assert.assertEquals(original.get("generation"),after.get("generation"));Assert.assertTrue(after.get("pendingOperationUuid").isJsonNull());
        Assert.assertEquals(database,fixture.database.snapshot(80L));Assert.assertEquals(identity,fixture.localPublicFingerprint());
        Assert.assertEquals(0,fixture.nativeActions().stream().filter("render-maintenance-resume-source"::equals).count());
        Assert.assertEquals(1,fixture.nativeActions().stream().filter("render-activate"::equals).count());
        Assert.assertEquals(1,fixture.nativeActions().stream().filter("rollback"::equals).count());
    }

    @Test public void activeFourProtocolStageFailureExplicitlyResumesItsVerifiedOriginalSource()throws Exception {
        primaryMappedFixture();fixture.manager.prepareConfigurationCloneInitialBaseline(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        fixture.manager.authorizeConfigurationCloneProfile(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        fixture.manager.applyStorageServiceApprovedMaintenance(fixture.existingApprovedCommand("active-source-before-failure"));
        JsonObject original=fixture.generation("operation generation status",null);String database=fixture.database.snapshot(80L);
        var command=fixture.activeApprovedCommand("active-source-stage-failure");fixture.rejectStageOnce=true;
        Assert.assertEquals("Fixture transport rejected stage before activation",Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
            ()->fixture.manager.applyStorageServiceApprovedMaintenance(command)).getMessage());
        StorageServiceOperationVO failed=fixture.operationRows.values().stream().filter(row->"SERVICE_MAINTENANCE".equals(row.getAction())&&row.getRevision()==3).findFirst().orElseThrow();
        Assert.assertEquals("RECOVERY_REQUIRED",failed.getState());Assert.assertTrue(fixture.obj(failed.getPreviousSnapshotJson()).has("adServiceCipherCheckpoint"));
        JsonObject args=new JsonObject();args.addProperty("operationid",failed.getId());
        var reconcile=(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd)StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd.class,args);
        fixture.manager.reconcileStorageServiceOperation(reconcile);
        Assert.assertEquals("ROLLED_BACK",fixture.operationRows.get(failed.getId()).getState());
        JsonObject current=fixture.generation("operation generation status",null);Assert.assertEquals(original.get("generation"),current.get("generation"));
        Assert.assertEquals(database,fixture.database.snapshot(80L));Assert.assertEquals("IN_SYNC",current.get("generationStatus").getAsString());
        Assert.assertTrue(current.get("pendingOperationUuid").isJsonNull());
        JsonObject observed=fixture.generation("operation observe",new JsonObject());
        for(String domain:java.util.List.of("NFS","SMB","ISCSI","NVMEOF"))Assert.assertTrue(observed.getAsJsonObject("realRenderedProtocolVerification").get(domain).getAsBoolean());
        Assert.assertEquals(1,fixture.nativeActions().stream().filter("render-maintenance-resume-source"::equals).count());
    }

    @Test public void checkpointlessApprovedServiceClosesTheSameVerifiedInitialSourceThenRestoresNormally()throws Exception {
        fixture.manager.prepareConfigurationCloneInitialBaseline(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        fixture.manager.authorizeConfigurationCloneProfile(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        JsonObject source=fixture.generation("operation generation status",null),lkg=fixture.initialLkgIdentity();
        String volumes=new com.google.gson.Gson().toJson(fixture.volumeRows),creator=fixture.artifactRows.get(900L).getMetadataJson();
        var command=fixture.existingApprovedCommand("approved-before-key-publication");
        try(var fault=Mockito.mockStatic(StorageIdentityCapsule.class,Mockito.CALLS_REAL_METHODS)) {
            fault.when(()->StorageIdentityCapsule.protectedPrivateKey(Mockito.any(java.security.KeyPair.class)))
                .thenThrow(new com.cloud.utils.exception.CloudRuntimeException("Configuration artifact directory is unavailable",
                    new java.nio.file.AccessDeniedException("synthetic-owned-rendered-keys")));
            Assert.assertEquals("Configuration artifact directory is unavailable",Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                ()->fixture.manager.applyStorageServiceApprovedMaintenance(command)).getMessage());
        }
        StorageServiceOperationVO failed=fixture.operationRows.values().stream().filter(v->"SERVICE_MAINTENANCE".equals(v.getAction())).findFirst().orElseThrow();
        Assert.assertEquals("RECOVERY_REQUIRED",failed.getState());Assert.assertEquals(0,failed.getProgress());String diagnostic=failed.getDiagnostic();
        Assert.assertEquals(java.util.Set.of("schemaVersion","instanceId","tables"),fixture.obj(failed.getPreviousSnapshotJson()).keySet());
        Assert.assertEquals(1,fixture.nativeActions().stream().filter("begin"::equals).count());Assert.assertFalse(fixture.nativeActions().contains("service-enter"));
        JsonObject args=new JsonObject();args.addProperty("operationid",failed.getId());
        var reconcile=(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd)StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd.class,args);
        for(String fault:java.util.List.of("actor","phase","progress","checkpoint","database","lkg","fileUuid","rawData","boot","foreignTarget")) {
            StorageServiceOperationVO before=fixture.copy(fixture.operationRows.get(failed.getId()),StorageServiceOperationVO.class);
            String database=fixture.database.snapshot(80L),os=Files.readString(fixture.nativeCase.resolve("witness-os-fixture.json"));
            java.util.Map<Long,StorageConfigArtifactVO> artifacts=new java.util.LinkedHashMap<>();for(var row:fixture.artifactRows.entrySet())artifacts.put(row.getKey(),fixture.copy(row.getValue(),StorageConfigArtifactVO.class));
            if(fault.equals("actor"))fixture.operationRows.get(failed.getId()).setCreatedBy(3);
            if(fault.equals("phase"))fixture.operationRows.get(failed.getId()).setPhase("APPLYING");
            if(fault.equals("progress"))fixture.operationRows.get(failed.getId()).setProgress(1);
            if(fault.equals("checkpoint")){JsonObject previous=fixture.obj(before.getPreviousSnapshotJson());previous.add("nativeGeneration",new JsonObject());fixture.operationRows.get(failed.getId()).setPreviousSnapshotJson(previous.toString());}
            if(fault.equals("database"))((StorageServiceProtocolVO)fixture.database.rows.get("storage_service_protocol").values().iterator().next()).setPort(2050);
            if(fault.equals("lkg"))for(var row:fixture.artifactRows.values())if("ACTIVE_LKG".equals(row.getState()))row.setSha256("0".repeat(64));
            if(java.util.Set.of("fileUuid","rawData","boot","foreignTarget").contains(fault)){
                JsonObject osValue=fixture.obj(os);
                if(fault.equals("fileUuid"))osValue.getAsJsonArray("dataVolumes").get(0).getAsJsonObject().addProperty("filesystemUuid",fixture.id(992));
                if(fault.equals("rawData"))osValue.getAsJsonArray("dataVolumes").get(2).getAsJsonObject().addProperty("sizeBytes",3L<<30);
                if(fault.equals("boot"))osValue.addProperty("bootId",fixture.id(993));
                if(fault.equals("foreignTarget"))osValue.addProperty("foreignIscsiTarget","iqn.2026-10.example:foreign");
                Files.writeString(fixture.nativeCase.resolve("witness-os-fixture.json"),osValue.toString());
            }
            Assert.assertThrows(fault,RuntimeException.class,()->fixture.manager.reconcileStorageServiceOperation(reconcile));
            if(fault.equals("foreignTarget")){
                java.nio.file.Path orphan=fixture.nativeCase.resolve("sys/kernel/config/target/iscsi/iqn.2026-10.example:foreign");
                Assert.assertFalse(Files.isSymbolicLink(orphan));Assert.assertTrue(orphan.toRealPath().startsWith(fixture.nativeCase.toRealPath()));
                Files.delete(orphan); // Revert only the empty owned OS fixture node introduced by this negative.
            }
            fixture.operationRows.put(failed.getId(),before);fixture.database.restore(80L,database);fixture.artifactRows.clear();fixture.artifactRows.putAll(artifacts);
            Files.writeString(fixture.nativeCase.resolve("witness-os-fixture.json"),os);
        }
        fixture.loseCheckpointlessFinalizationOnce=true;
        var completionFailure=Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->fixture.manager.reconcileStorageServiceOperation(reconcile));
        if(!"Resource-finalized operation completion could not be persisted".equals(completionFailure.getMessage()))throw completionFailure;
        Assert.assertEquals("RECOVERY_REQUIRED",fixture.operationRows.get(failed.getId()).getState());
        Assert.assertTrue(fixture.obj(fixture.operationRows.get(failed.getId()).getResultJson()).has("_checkpointlessServiceSourceRecovery"));
        fixture.manager.reconcileStorageServiceOperation(reconcile);
        StorageServiceOperationVO recovered=fixture.operationRows.get(failed.getId());Assert.assertEquals("ROLLED_BACK",recovered.getState());Assert.assertEquals(100,recovered.getProgress());
        Assert.assertTrue(recovered.getDiagnostic().startsWith(diagnostic));Assert.assertEquals(fixture.initialDatabaseSnapshot,fixture.database.snapshot(80L));
        Assert.assertEquals(volumes,new com.google.gson.Gson().toJson(fixture.volumeRows));Assert.assertEquals(lkg,fixture.initialLkgIdentity());
        Assert.assertEquals(source.get("generation"),fixture.generation("operation generation status",null).get("generation"));fixture.assertEmptySourceRuntime();
        java.util.List<String> actions=fixture.nativeActions();Assert.assertEquals(1,actions.stream().filter("begin"::equals).count());
        for(String action:java.util.List.of("service-enter","service-release","render-stage","render-activate","restore","rollback"))Assert.assertFalse(action,actions.contains(action));
        fixture.manager.applyStorageServiceApprovedMaintenance(fixture.existingApprovedCommand("approved-after-checkpointless-source-recovery"));
        assertCompletedRestore(volumes,901L);Assert.assertEquals(creator,fixture.artifactRows.get(900L).getMetadataJson());
    }

    @Test public void unheldPreparedServiceAbortsOnlyItsOwnPendingAndSafelyRetriesUnknownAndFinalSave()throws Exception {
        fixture.manager.prepareConfigurationCloneInitialBaseline(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        fixture.manager.authorizeConfigurationCloneProfile(fixture.instanceRows.get(80L),fixture.artifactRows.get(900L),fixture.executionPlan());
        JsonObject original=fixture.generation("operation generation status",null),lkg=fixture.initialLkgIdentity();
        String volumes=new com.google.gson.Gson().toJson(fixture.volumeRows),creator=fixture.artifactRows.get(900L).getMetadataJson();
        fixture.rejectServiceEnterBeforeHoldOnce=true;var command=fixture.existingApprovedCommand("approved-enter-fence-rejected");
        var failure=Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->fixture.manager.applyStorageServiceApprovedMaintenance(command));
        if(fixture.rejectServiceEnterBeforeHoldOnce)throw failure;
        Assert.assertEquals("Fixture rejected formatter lock fence before SERVICE hold",failure.getMessage());
        StorageServiceOperationVO failed=fixture.operationRows.values().stream().filter(v->"SERVICE_MAINTENANCE".equals(v.getAction())).findFirst().orElseThrow();
        Assert.assertEquals("RECOVERY_REQUIRED",failed.getState());Assert.assertEquals(0,failed.getProgress());String diagnostic=failed.getDiagnostic();
        JsonObject snapshot=fixture.obj(failed.getPreviousSnapshotJson()),rendered=snapshot.getAsJsonObject("renderedGeneration");
        Assert.assertEquals("PREPARED",rendered.get("phase").getAsString());Assert.assertFalse(snapshot.has("approvedServiceMaintenanceHeld"));
        Assert.assertEquals(failed.getUuid(),fixture.generation("operation generation status",null).get("pendingOperationUuid").getAsString());
        Assert.assertEquals(2,fixture.nativeActions().stream().filter("begin"::equals).count());Assert.assertFalse(fixture.nativeActions().contains("service-enter"));
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->fixture.manager.recoverRenderedStorageWriter(fixture.instanceRows.get(80L),failed));
        Assert.assertEquals(0,fixture.nativeActions().stream().filter("rollback"::equals).count());
        JsonObject args=new JsonObject();args.addProperty("operationid",failed.getId());
        var reconcile=(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd)StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd.class,args);
        for(String fault:java.util.List.of("actor","progress","nativeSource","manifest","stage","database","rawBinding")) {
            StorageServiceOperationVO saved=fixture.copy(fixture.operationRows.get(failed.getId()),StorageServiceOperationVO.class);
            String database=fixture.database.snapshot(80L),path=fixture.volumeRows.get(503L).getPath();
            JsonObject changed=fixture.obj(saved.getPreviousSnapshotJson());
            if(fault.equals("actor"))fixture.operationRows.get(failed.getId()).setCreatedBy(3);
            if(fault.equals("progress"))fixture.operationRows.get(failed.getId()).setProgress(1);
            if(fault.equals("nativeSource"))changed.getAsJsonObject("nativeGeneration").getAsJsonObject("previous").addProperty("revision",9);
            if(fault.equals("manifest"))changed.getAsJsonObject("renderedGeneration").getAsJsonObject("previousManifest").addProperty("manifestSha256","0".repeat(64));
            if(fault.equals("stage")){JsonObject receipt=new JsonObject();receipt.addProperty("phase","PREPARED");receipt.add("staged",new JsonObject());changed.getAsJsonObject("renderedGeneration").add("receipt",receipt);}
            if(java.util.Set.of("nativeSource","manifest","stage").contains(fault))fixture.operationRows.get(failed.getId()).setPreviousSnapshotJson(changed.toString());
            if(fault.equals("database"))((StorageServiceProtocolVO)fixture.database.rows.get("storage_service_protocol").values().iterator().next()).setPort(2050);
            if(fault.equals("rawBinding"))org.springframework.test.util.ReflectionTestUtils.setField(fixture.volumeRows.get(503L),"path","foreign-raw-backing");
            Assert.assertThrows(fault,RuntimeException.class,()->fixture.manager.reconcileStorageServiceOperation(reconcile));
            Assert.assertEquals(0,fixture.nativeActions().stream().filter("rollback"::equals).count());
            fixture.operationRows.put(failed.getId(),saved);fixture.database.restore(80L,database);org.springframework.test.util.ReflectionTestUtils.setField(fixture.volumeRows.get(503L),"path",path);
        }
        fixture.driftNoHeldAfterIntentOnce=true;
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->fixture.manager.reconcileStorageServiceOperation(reconcile));
        Assert.assertFalse(fixture.driftNoHeldAfterIntentOnce);Assert.assertEquals(0,fixture.nativeActions().stream().filter("rollback"::equals).count());
        fixture.database.restore(80L,fixture.initialDatabaseSnapshot);
        fixture.loseNoHeldRollbackResponseOnce=true;
        var unknown=Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->fixture.manager.reconcileStorageServiceOperation(reconcile));
        if(fixture.loseNoHeldRollbackResponseOnce)throw unknown;
        Assert.assertEquals(1,fixture.nativeActions().stream().filter("rollback"::equals).count());
        Assert.assertTrue(fixture.generation("operation generation status",null).get("pendingOperationUuid").isJsonNull());
        Assert.assertEquals("RECOVERY_REQUIRED",fixture.operationRows.get(failed.getId()).getState());
        Assert.assertTrue(fixture.obj(fixture.operationRows.get(failed.getId()).getResultJson()).has("_initialUnheldServiceSourceRecovery"));
        fixture.loseNoHeldFinalizationOnce=true;
        var saveFailure=Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->fixture.manager.reconcileStorageServiceOperation(reconcile));
        if(!"Resource-finalized operation completion could not be persisted".equals(saveFailure.getMessage()))throw saveFailure;
        fixture.manager.reconcileStorageServiceOperation(reconcile);
        StorageServiceOperationVO recovered=fixture.operationRows.get(failed.getId());Assert.assertEquals("ROLLED_BACK",recovered.getState());Assert.assertEquals(100,recovered.getProgress());Assert.assertTrue(recovered.getDiagnostic().startsWith(diagnostic));
        Assert.assertEquals(original.get("generation"),fixture.generation("operation generation status",null).get("generation"));
        Assert.assertEquals(fixture.initialDatabaseSnapshot,fixture.database.snapshot(80L));Assert.assertEquals(volumes,new com.google.gson.Gson().toJson(fixture.volumeRows));Assert.assertEquals(lkg,fixture.initialLkgIdentity());fixture.assertEmptySourceRuntime();
        java.util.List<String> actions=fixture.nativeActions();Assert.assertEquals(1,actions.stream().filter("rollback"::equals).count());Assert.assertEquals(2,actions.stream().filter("begin"::equals).count());
        for(String action:java.util.List.of("service-enter","service-release","render-maintenance-resume-source","render-stage","render-activate","restore"))Assert.assertFalse(action,actions.contains(action));
        int beforeDuplicate=actions.size();Assert.assertThrows(RuntimeException.class,()->fixture.manager.reconcileStorageServiceOperation(reconcile));Assert.assertEquals(beforeDuplicate,fixture.nativeActions().size());
        fixture.manager.applyStorageServiceApprovedMaintenance(fixture.existingApprovedCommand("approved-after-own-nohold-abort"));
        assertCompletedRestore(volumes,901L);Assert.assertEquals(creator,fixture.artifactRows.get(900L).getMetadataJson());
    }

    @Test public void reversedArchivedProtocolsPreserveSpecificAndLegacyDefaultEndpoints()throws Exception {
        JsonArray changes=fixture.plan.getAsJsonArray("create");java.util.List<JsonElement> protocols=new java.util.ArrayList<>();
        for(JsonElement item:changes)if("protocols".equals(item.getAsJsonObject().get("kind").getAsString()))protocols.add(item.deepCopy());
        java.util.Collections.reverse(protocols);int index=0;
        for(int i=0;i<changes.size();i++)if("protocols".equals(changes.get(i).getAsJsonObject().get("kind").getAsString()))changes.set(i,protocols.get(index++));
        requireCompletedRestore();
    }

    @Test public void foreignKernelTargetIsRejectedBeforeTheNormalBaselineOrDomainWriter()throws Exception {
        JsonObject os=fixture.obj(Files.readString(fixture.nativeCase.resolve("witness-os-fixture.json")));
        os.addProperty("foreignIscsiTarget","iqn.2026-10.example:foreign");
        Files.writeString(fixture.nativeCase.resolve("witness-os-fixture.json"),os.toString());
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                ()->fixture.manager.storageServiceConfiguration(fixture.reviewedCommand()));
        fixture.assertNoBaselineCommit();
        Assert.assertFalse(fixture.nativeActions().contains("begin"));
        Assert.assertEquals(fixture.initialDatabaseSnapshot,fixture.database.snapshot(80L));
    }

    @Test public void primaryMappedFailureRestoresInitialNfsThenRetriesTheSameCreator()throws Exception {
        primaryMappedFixture();assertRejectedStageRestoresSourceThenRetries();
    }

    @Test public void rejectedStageRestoresTheVerifiedEmptySourceAndReleasesOnlyItsOwnHold()throws Exception {
        assertRejectedStageRestoresSourceThenRetries();
    }

    private void assertRejectedStageRestoresSourceThenRetries()throws Exception {
        fixture.rejectStageOnce=true;
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                ()->fixture.manager.storageServiceConfiguration(fixture.reviewedCommand()));
        Assert.assertFalse("The actual render-stage fault must be reached",fixture.rejectStageOnce);
        StorageServiceOperationVO operation=fixture.operationRows.values().stream()
                .filter(row->"applystorageserviceconfigrestoreresponse".equalsIgnoreCase(row.getAction())).findFirst().orElseThrow();
        Assert.assertEquals(operation.getDiagnostic(),"ROLLED_BACK",operation.getState());
        Assert.assertEquals(fixture.initialDatabaseSnapshot,fixture.database.snapshot(80L));
        JsonObject state=fixture.generation("operation generation status",null);
        Assert.assertEquals("IN_SYNC",state.get("generationStatus").getAsString());
        Assert.assertTrue(state.get("pendingOperationUuid").isJsonNull());Assert.assertEquals(1,state.get("runtimeRevision").getAsLong());
        JsonObject scope=new JsonObject();scope.addProperty("instanceUuid",fixture.TARGET);
        scope.addProperty("operationUuid",operation.getUuid());scope.addProperty("revision",2);scope.addProperty("maintenanceUuid",operation.getUuid());
        Assert.assertFalse(fixture.generation("operation maintenance service-status",scope).get("bootHeld").getAsBoolean());
        Assert.assertEquals(1,fixture.nativeActions().stream().filter("commit"::equals).count());
        String oldOperation=operation.getUuid(),disks=new com.google.gson.Gson().toJson(fixture.volumeRows);
        JsonObject creator=fixture.obj(fixture.artifactRows.get(900L).getMetadataJson()).getAsJsonObject("cloneAllocation").deepCopy();
        fixture.manager.storageServiceConfiguration(fixture.freshReviewCommand());
        assertCompletedRestore(disks);
        Assert.assertEquals("ROLLED_BACK",fixture.operationRows.get(operation.getId()).getState());
        JsonObject metadata=fixture.obj(fixture.artifactRows.get(900L).getMetadataJson());
        Assert.assertNotEquals(oldOperation,metadata.getAsJsonObject("cloneRestoreOperation").get("operationUuid").getAsString());
        Assert.assertEquals(creator,metadata.get("cloneAllocation"));
        Assert.assertEquals(3,fixture.nativeActions().stream().filter("begin"::equals).count());
    }

    @Test public void firstStagingReceiptSaveFailureUsesOnlyFreshDurableSourceForRollback()throws Exception {
        fixture.loseFirstStageReceiptOnce=true;
        requireRejectedStageSourceRollback();
        Assert.assertFalse(fixture.nativeActions().contains("render-stage"));
    }

    @Test public void verifiedTargetResponseLossRestoresOriginalSourceAndRetainedData()throws Exception {
        fixture.rejectAfterActivateOnce=true;
        requireRejectedStageSourceRollback();
        Assert.assertTrue(fixture.nativeActions().contains("render-activate"));
        Assert.assertTrue(fixture.nativeActions().contains("render-rollback"));
    }

    private void requireRejectedStageSourceRollback()throws Exception {
        String retainedVolumes=new com.google.gson.Gson().toJson(fixture.volumeRows);
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                ()->fixture.manager.storageServiceConfiguration(fixture.reviewedCommand()));
        StorageServiceOperationVO operation=fixture.operationRows.values().stream()
                .filter(row->"applystorageserviceconfigrestoreresponse".equalsIgnoreCase(row.getAction())).findFirst().orElseThrow();
        Assert.assertEquals(operation.getDiagnostic(),"ROLLED_BACK",operation.getState());
        Assert.assertEquals(fixture.initialDatabaseSnapshot,fixture.database.snapshot(80L));
        JsonObject state=fixture.generation("operation generation status",null);
        Assert.assertEquals("IN_SYNC",state.get("generationStatus").getAsString());
        Assert.assertTrue(state.get("pendingOperationUuid").isJsonNull());Assert.assertEquals(1,state.get("runtimeRevision").getAsLong());
        JsonObject scope=new JsonObject();scope.addProperty("instanceUuid",fixture.TARGET);
        scope.addProperty("operationUuid",operation.getUuid());scope.addProperty("revision",2);scope.addProperty("maintenanceUuid",operation.getUuid());
        Assert.assertFalse(fixture.generation("operation maintenance service-status",scope).get("bootHeld").getAsBoolean());
        Assert.assertEquals(1,fixture.nativeActions().stream().filter("commit"::equals).count());
        Assert.assertEquals(retainedVolumes,new com.google.gson.Gson().toJson(fixture.volumeRows));
        JsonObject baseline=fixture.obj(fixture.artifactRows.get(900L).getMetadataJson()).getAsJsonObject("cloneInitialBaseline");
        Assert.assertNotNull(baseline);Assert.assertEquals(state.get("generation"),baseline.get("generation"));
        Assert.assertNotNull(fixture.sourceLkgIdentity);
        Assert.assertEquals(fixture.sourceLkgIdentity,fixture.initialLkgIdentity());
        Assert.assertEquals(baseline.get("generation"),fixture.sourceLkgIdentity.get("nativeGeneration"));
        Assert.assertEquals(baseline.get("baselineOperationUuid"),fixture.sourceLkgIdentity.get("sourceOperationUuid"));
        fixture.assertEmptySourceRuntime();
        Assert.assertNotNull(fixture.sourceLocalFingerprint);
        Assert.assertEquals(fixture.sourceLocalFingerprint,fixture.localPublicFingerprint());
    }

    @Test public void lostFinalArtifactPublicationIsFinalizedByFreshReviewWithoutAnotherNativeOrDomainWriter()throws Exception {
        fixture.loseCompletionOnce=true;
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                ()->fixture.manager.storageServiceConfiguration(fixture.reviewedCommand()));
        JsonObject before=fixture.generation("operation generation status",null);
        Assert.assertEquals(2,before.get("runtimeRevision").getAsLong());Assert.assertEquals("IN_SYNC",before.get("generationStatus").getAsString());
        Assert.assertEquals("FAILED",fixture.obj(fixture.artifactRows.get(900L).getMetadataJson()).get("restoreState").getAsString());
        String desired=fixture.database.snapshot(80L),disks=new com.google.gson.Gson().toJson(fixture.volumeRows);
        int operations=fixture.operationRows.size();long commits=fixture.nativeActions().stream().filter("commit"::equals).count();
        fixture.manager.storageServiceConfiguration(fixture.freshReviewCommand());
        Assert.assertEquals(before.get("generation"),fixture.generation("operation generation status",null).get("generation"));
        Assert.assertEquals(desired,fixture.database.snapshot(80L));Assert.assertEquals(disks,new com.google.gson.Gson().toJson(fixture.volumeRows));
        Assert.assertEquals(operations,fixture.operationRows.size());Assert.assertEquals(commits,fixture.nativeActions().stream().filter("commit"::equals).count());
        Assert.assertEquals(1,fixture.nativeActions().stream().filter("render-activate"::equals).count());
        JsonObject metadata=fixture.obj(fixture.artifactRows.get(900L).getMetadataJson());
        Assert.assertEquals("COMPLETE",metadata.get("restoreState").getAsString());
        Assert.assertFalse(metadata.has("errorCode"));
        Assert.assertEquals("CONFIG_RESTORE_FAILED",metadata.getAsJsonObject("clonePublicationRecovery").get("previousErrorCode").getAsString());
        Assert.assertEquals(metadata.get("cloneRestoreOperation"),metadata.getAsJsonObject("clonePublicationRecovery").get("operation"));
    }

    @Test public void publicationFinalizationRejectsAChangedCurrentTargetWithoutReapplyingTheArchive()throws Exception {
        fixture.loseCompletionOnce=true;
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                ()->fixture.manager.storageServiceConfiguration(fixture.reviewedCommand()));
        StorageServiceProtocolVO changed=(StorageServiceProtocolVO)fixture.database.rows.get("storage_service_protocol").values().iterator().next();
        changed.setEnabled(false);String desired=fixture.database.snapshot(80L);
        int operations=fixture.operationRows.size();long commits=fixture.nativeActions().stream().filter("commit"::equals).count();
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                ()->fixture.manager.storageServiceConfiguration(fixture.freshReviewCommand()));
        Assert.assertEquals(desired,fixture.database.snapshot(80L));Assert.assertEquals(operations,fixture.operationRows.size());
        Assert.assertEquals(commits,fixture.nativeActions().stream().filter("commit"::equals).count());
        Assert.assertEquals("FAILED",fixture.obj(fixture.artifactRows.get(900L).getMetadataJson()).get("restoreState").getAsString());
    }

    @Test public void anotherAdministratorCannotFinalizeTheOriginalActorsCompletedRestore()throws Exception {
        fixture.loseCompletionOnce=true;
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                ()->fixture.manager.storageServiceConfiguration(fixture.reviewedCommand()));
        JsonObject committed=fixture.generation("operation generation status",null);
        Assert.assertEquals("IN_SYNC",committed.get("generationStatus").getAsString());
        Assert.assertEquals(2,committed.get("runtimeRevision").getAsLong());
        Assert.assertNotNull(fixture.obj(fixture.artifactRows.get(900L).getMetadataJson()).getAsJsonObject("cloneRestoreOperation"));
        org.apache.cloudstack.context.CallContext original=org.apache.cloudstack.context.CallContext.current();
        com.cloud.user.User other=Mockito.mock(com.cloud.user.User.class);Mockito.when(other.getId()).thenReturn(3L);
        org.apache.cloudstack.context.CallContext.register(other,original.getCallingAccount());
        int operations=fixture.operationRows.size();long commits=fixture.nativeActions().stream().filter("commit"::equals).count();
        try {
            RuntimeException rejected=Assert.assertThrows(RuntimeException.class,
                    ()->fixture.manager.storageServiceConfiguration(fixture.freshReviewCommand()));
            Assert.assertTrue(rejected.getMessage(),rejected.getMessage().contains("actor"));
        } finally {org.apache.cloudstack.context.CallContext.unregister();}
        Assert.assertEquals(operations,fixture.operationRows.size());Assert.assertEquals(commits,fixture.nativeActions().stream().filter("commit"::equals).count());
    }

    @Test public void anotherOperationReferenceCannotPublishTheCommittedTarget()throws Exception {
        fixture.loseCompletionOnce=true;
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                ()->fixture.manager.storageServiceConfiguration(fixture.reviewedCommand()));
        StorageConfigArtifactVO artifact=fixture.artifactRows.get(900L);JsonObject metadata=fixture.obj(artifact.getMetadataJson());
        StorageServiceOperationVO baseline=fixture.operationRows.values().stream().filter(row->"verifyStorageServiceConfiguration".equals(row.getAction())).findFirst().orElseThrow();
        metadata.getAsJsonObject("cloneRestoreOperation").addProperty("operationUuid",baseline.getUuid());
        metadata.getAsJsonObject("cloneRestoreOperation").addProperty("revision",baseline.getRevision());artifact.setMetadataJson(metadata.toString());
        int operations=fixture.operationRows.size();long commits=fixture.nativeActions().stream().filter("commit"::equals).count();
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                ()->fixture.manager.storageServiceConfiguration(fixture.freshReviewCommand()));
        Assert.assertEquals(operations,fixture.operationRows.size());Assert.assertEquals(commits,fixture.nativeActions().stream().filter("commit"::equals).count());
    }

    private void requireCompletedRestore()throws Exception {
        String disks=new com.google.gson.Gson().toJson(fixture.volumeRows);
        var response=fixture.manager.storageServiceConfiguration(fixture.reviewedCommand());
        assertCompletedArtifactResponse(fixture.obj(new com.google.gson.Gson().toJson(response)));
        assertCompletedRestore(disks);
    }

    private void assertCompletedArtifactResponse(JsonObject response) {
        JsonObject metadata=fixture.obj(response.get("result").getAsString()).getAsJsonObject("metadata");
        Assert.assertEquals("COMPLETE",metadata.get("restoreState").getAsString());
        Assert.assertFalse("A successful restore must not return a previous attempt's failure",metadata.has("errorCode"));
    }

    private void assertCompletedRestore(String disks)throws Exception {assertCompletedRestore(disks,900L);}
    private void assertCompletedRestore(String disks,long artifactId)throws Exception {
        JsonObject nativeState=fixture.generation("operation generation status",null);
        Assert.assertEquals("IN_SYNC",nativeState.get("generationStatus").getAsString());
        Assert.assertTrue(nativeState.get("pendingOperationUuid").isJsonNull());
        Assert.assertEquals(2,nativeState.get("runtimeRevision").getAsLong());
        java.util.List<StorageConfigArtifactVO> points=fixture.artifactRows.values().stream()
                .filter(row->row.getInstanceId()==80L && "RESTORE_POINT".equals(row.getKind()) && "ACTIVE_LKG".equals(row.getState()))
                .collect(java.util.stream.Collectors.toList());
        Assert.assertEquals(1,points.size());StorageConfigArtifactVO point=points.get(0);
        Assert.assertEquals(2,point.getDesiredRevision());
        StorageServiceOperationVO operation=fixture.operationRows.get(point.getSourceOperationId());
        Assert.assertNotNull(operation);Assert.assertEquals(80L,operation.getInstanceId());Assert.assertEquals("COMPLETE",operation.getState());
        Assert.assertEquals(nativeState.getAsJsonObject("generation").get("operationUuid").getAsString(),operation.getUuid());
        JsonObject pointMetadata=fixture.obj(point.getMetadataJson());
        Assert.assertEquals(nativeState.get("generation"),pointMetadata.get("nativeGeneration"));
        StorageConfigRuntimeEvidence.requireStored(pointMetadata.getAsJsonObject("runtimeEvidence"),
                fixture.TARGET,operation.getUuid(),2,StorageConfigArchive.validate(fixture.store.read(point.getUuid(),point.getSha256())));
        JsonObject rendered=fixture.generation("operation generation render-status",new JsonObject());
        Assert.assertTrue(rendered.get("success").getAsBoolean());Assert.assertFalse(rendered.get("bootHeld").getAsBoolean());
        JsonObject scope=new JsonObject();scope.add("instanceUuid",nativeState.getAsJsonObject("generation").get("instanceUuid"));
        scope.add("operationUuid",nativeState.getAsJsonObject("generation").get("operationUuid"));scope.addProperty("revision",2);
        Assert.assertEquals(scope,rendered.getAsJsonObject("current").get("scope"));
        Assert.assertEquals(scope,rendered.getAsJsonObject("activation").get("scope"));
        Assert.assertEquals(nativeState.get("configurationSha256"),rendered.getAsJsonObject("current").get("configurationSha256"));
        Assert.assertEquals("COMPLETE",rendered.getAsJsonObject("activation").get("phase").getAsString());
        Assert.assertEquals(rendered.getAsJsonObject("current").get("manifestSha256"),rendered.getAsJsonObject("activation").get("targetSha256"));
        JsonObject heldScope=scope.deepCopy();heldScope.add("maintenanceUuid",scope.get("operationUuid"));
        Assert.assertFalse(fixture.generation("operation maintenance service-status",heldScope).get("bootHeld").getAsBoolean());
        Assert.assertEquals("COMPLETE",fixture.obj(fixture.artifactRows.get(artifactId).getMetadataJson()).get("restoreState").getAsString());
        Assert.assertFalse(fixture.obj(fixture.artifactRows.get(artifactId).getMetadataJson()).has("errorCode"));
        Assert.assertEquals(fixture.primaryNfsFourProtocols?14:16,fixture.database.rows.values().stream().mapToInt(java.util.Map::size).sum());
        Assert.assertEquals(fixture.primaryNfsFourProtocols?4:6,fixture.database.rows.get("storage_service_protocol").size());
        JsonObject originalDefault=fixture.obj(fixture.initialDatabaseSnapshot).getAsJsonArray("tables").get(0).getAsJsonObject().getAsJsonArray("rows").get(0).getAsJsonObject();
        Assert.assertTrue(fixture.database.rows.get("storage_service_protocol").containsKey(originalDefault.get("id").getAsLong()));
        StorageServiceProtocolVO retained=(StorageServiceProtocolVO)fixture.database.rows.get("storage_service_protocol").get(originalDefault.get("id").getAsLong());
        Assert.assertEquals(originalDefault.get("uuid").getAsString(),retained.getUuid());Assert.assertEquals(StorageServiceInstance.Protocol.NFS,retained.getProtocol());
        Assert.assertEquals(disks,new com.google.gson.Gson().toJson(fixture.volumeRows));
        Assert.assertEquals(2,fixture.nativeActions().stream().filter("commit"::equals).count());
        Assert.assertTrue(fixture.nativeActions().stream().filter("finish"::equals).count()>=2);
        java.util.List<String> trace=fixture.nativeActions();int finalBegin=trace.lastIndexOf("begin");
        Assert.assertTrue("Final restore begin is absent",finalBegin>=0);
        java.util.List<String> completedCycle=trace.subList(finalBegin,trace.size());
        java.util.List<String> ordered=java.util.List.of("service-enter","render-stage","render-activate","render-finalize","service-release");
        for(String action:ordered)Assert.assertTrue(action,completedCycle.contains(action));
        for(int i=1;i<ordered.size();i++)Assert.assertTrue(ordered.get(i-1)+" before "+ordered.get(i),
                completedCycle.indexOf(ordered.get(i-1))<completedCycle.indexOf(ordered.get(i)));
    }

    /** Surefire class directories omit the normal JAR manifest; no runtime/generation result is replaced. */
    private static final class BuildManifestManager extends StorageServiceManagerImpl {
        private final String buildVersion;
        Runnable beforeInitialNfsReuse;
        @Override protected void prepareConfigurationInitialNfsReuse(StorageServiceInstanceVO instance,JsonObject plan) {
            if(beforeInitialNfsReuse!=null){Runnable hook=beforeInitialNfsReuse;beforeInitialNfsReuse=null;hook.run();}
            super.prepareConfigurationInitialNfsReuse(instance,plan);
        }
        BuildManifestManager(java.nio.file.Path repository)throws Exception {
            javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            org.w3c.dom.Document pom=factory.newDocumentBuilder().parse(repository.resolve("pom.xml").toFile());String found=null;
            for(org.w3c.dom.Node node=pom.getDocumentElement().getFirstChild();node!=null;node=node.getNextSibling())
                if(node.getNodeType()==org.w3c.dom.Node.ELEMENT_NODE && "version".equals(node.getLocalName()))found=node.getTextContent().trim();
            Assert.assertNotNull("Exact repository build version is required",found);buildVersion=found;
        }
        @Override protected JsonObject configurationInstanceMetadata(StorageServiceInstanceVO instance) {
            JsonObject result=super.configurationInstanceMetadata(instance);
            if("unknown".equals(result.get("productVersion").getAsString()))result.addProperty("productVersion",buildVersion);
            return result;
        }
    }

    private static final class Fixture extends StorageConfigInitialBaselineFlowTest {
    StorageConfigStatefulResourceFixture database;
    boolean rejectStageOnce;
    boolean rejectAfterActivateOnce;
    boolean loseFirstStageReceiptOnce;
    boolean loseCompletionOnce;
    boolean loseCheckpointlessFinalizationOnce;
    boolean rejectServiceEnterBeforeHoldOnce,loseNoHeldRollbackResponseOnce,loseNoHeldFinalizationOnce,driftNoHeldAfterIntentOnce,loseHistoricalHeldTerminalOnce,loseReleasedInitialFinalizationOnce;
    JsonObject recordedMapping;
    String transientCredentials;
    JsonObject sourceLocalFingerprint,sourceLkgIdentity;
    @Override @org.junit.Before public void setup()throws Exception{
        manager=new BuildManifestManager(repo);super.setup();database=initialDatabase;
        recordedMapping=new JsonObject();recordedMapping.add("createNew",plan.get("createNew").deepCopy());
        recordedMapping.add("volumes",plan.get("volumeMappings").deepCopy());recordedMapping.addProperty("runtimeBundleUuid",BUNDLE);
        String initialSource=null;for(JsonElement allocation:plan.getAsJsonObject("volumeAllocationPlan").getAsJsonArray("allocations"))
            if(allocation.getAsJsonObject().get("initial").getAsBoolean())initialSource=allocation.getAsJsonObject().get("sourceUuid").getAsString();
        Assert.assertNotNull(initialSource);recordedMapping.addProperty("initialVolumeSourceUuid",initialSource);plan.addProperty("initialVolumeSourceUuid",initialSource);
        JsonObject listeners=new JsonObject();listeners.addProperty("10.10.13.223","PRIMARY");if(primaryNfsFourProtocols)listeners.addProperty("0.0.0.0","PRIMARY");recordedMapping.add("listenerAddresses",listeners);
        StorageConfigArtifactVO artifact=artifactRows.get(900L);JsonObject metadata=obj(artifact.getMetadataJson()),requested=recordedMapping.deepCopy();
        requested.remove("listenerAddresses");metadata.add("requestedVolumeMapping",requested);metadata.add("plan",plan.deepCopy());artifact.setMetadataJson(metadata.toString());
        Mockito.doAnswer(call->{
            StorageServiceOperationVO value=call.getArgument(1);JsonObject prior=obj(value.getPreviousSnapshotJson()==null?"{}":value.getPreviousSnapshotJson());
            JsonObject rendered=prior.has("renderedGeneration")?prior.getAsJsonObject("renderedGeneration"):new JsonObject();
            if(loseReleasedInitialFinalizationOnce && "ROLLED_BACK".equals(value.getState()) && "ROLLED_BACK".equals(value.getPhase())) {loseReleasedInitialFinalizationOnce=false;return false;}
            if(loseHistoricalHeldTerminalOnce && "ROLLED_BACK".equals(value.getState()) && "INTERRUPTED_RENDERED_WRITER_ROLLED_BACK".equals(value.getPhase())) {loseHistoricalHeldTerminalOnce=false;return false;}
            if(loseNoHeldFinalizationOnce && "ROLLED_BACK".equals(value.getState()) && obj(value.getResultJson()).has("_initialUnheldServiceSourceRecovery")) {loseNoHeldFinalizationOnce=false;return false;}
            if(driftNoHeldAfterIntentOnce && obj(value.getResultJson()).has("_initialUnheldServiceSourceRecovery")) {
                driftNoHeldAfterIntentOnce=false;operationRows.put(call.getArgument(0),copy(value,StorageServiceOperationVO.class));
                ((StorageServiceProtocolVO)database.rows.get("storage_service_protocol").values().iterator().next()).setPort(2050);return true;
            }
            if(loseCheckpointlessFinalizationOnce && "ROLLED_BACK".equals(value.getState())
                    && obj(value.getResultJson()).has("_checkpointlessServiceSourceRecovery")) {loseCheckpointlessFinalizationOnce=false;return false;}
            if(loseFirstStageReceiptOnce && rendered.has("receipt") && "STAGING".equals(rendered.getAsJsonObject("receipt").get("phase").getAsString())) {
                loseFirstStageReceiptOnce=false;return false;
            }
            operationRows.put(call.getArgument(0),copy(value,StorageServiceOperationVO.class));return true;
        }).when(operations).update(Mockito.anyLong(),Mockito.any());
        Mockito.doAnswer(call->{
            StorageConfigArtifactVO value=call.getArgument(1);JsonObject state=obj(value.getMetadataJson());
            if(loseCompletionOnce && "COMPLETE".equals(state.has("restoreState")?state.get("restoreState").getAsString():null)) {
                loseCompletionOnce=false;throw new com.cloud.utils.exception.CloudRuntimeException("Fixture database lost only final artifact publication");
            }
            artifactRows.put(900L,copy(value,StorageConfigArtifactVO.class));return true;
        }).when(artifacts).update(Mockito.eq(900L),Mockito.any());
        AutoCloseable original=closeables.remove(closeables.size()-1);original.close();
        closeables.add(Mockito.mockConstruction(StorageServiceDesiredSnapshot.class,(mock,ctx)->{
            Mockito.when(mock.capture(Mockito.anyLong())).thenAnswer(c->database.snapshot(c.<Long>getArgument(0)));
            Mockito.doAnswer(c->{database.restore(c.<Long>getArgument(0),c.getArgument(1));return null;}).when(mock).restore(Mockito.anyLong(),Mockito.anyString());
        }));
    }
    @Override JsonObject generation(String operation,JsonObject request)throws Exception{
        Process child=startNativeTransport("controller");
        JsonObject wire=new JsonObject();wire.addProperty("operation",operation);wire.add("request",request==null?JsonNull.INSTANCE:request);
        try(var out=child.getOutputStream()){out.write((wire.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
        String output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);int exit=child.waitFor();
        JsonObject reply=nativeRpcObject(output,exit);
        if(operation.equals("identity capsule capabilities")&&reply.has("success")&&reply.get("success").getAsBoolean())reply.addProperty("protectedStdinTransport",true);
        if(operation.equals("identity capsule export-local-source")&&reply.get("success").getAsBoolean()) {
            sourceLocalFingerprint=localPublicFingerprint();sourceLkgIdentity=initialLkgIdentity();
        }
        return reply;
    }
    JsonObject initialLkgIdentity()throws Exception {
        java.util.List<StorageConfigArtifactVO> points=artifactRows.values().stream()
            .filter(row->row.getInstanceId()==80L&&"RESTORE_POINT".equals(row.getKind())&&"ACTIVE_LKG".equals(row.getState()))
            .collect(java.util.stream.Collectors.toList());
        Assert.assertEquals(1,points.size());StorageConfigArtifactVO point=points.get(0);
        Assert.assertEquals(1,point.getDesiredRevision());
        StorageServiceOperationVO source=operationRows.get(point.getSourceOperationId());
        Assert.assertNotNull(source);Assert.assertEquals("COMPLETE",source.getState());
        Assert.assertEquals("verifyStorageServiceConfiguration",source.getAction());
        JsonObject metadata=obj(point.getMetadataJson()),generation=metadata.getAsJsonObject("nativeGeneration");
        Assert.assertNotNull(generation);Assert.assertEquals(source.getUuid(),generation.get("operationUuid").getAsString());
        StorageConfigRuntimeEvidence.requireStored(metadata.getAsJsonObject("runtimeEvidence"),TARGET,source.getUuid(),1,
            StorageConfigArchive.validate(store.read(point.getUuid(),point.getSha256())));
        JsonObject result=new JsonObject();result.addProperty("uuid",point.getUuid());result.addProperty("sha256",point.getSha256());
        result.addProperty("sourceOperationUuid",source.getUuid());result.add("nativeGeneration",generation.deepCopy());return result;
    }
    JsonObject localPublicFingerprint()throws Exception {
        // Read only hashes and permissions of the owned OS fixture. No capsule/key/plaintext is returned.
        JsonObject result=new JsonObject();
        for(String name:java.util.List.of("rendered-os-fixture/etc/samba/smb.conf","rendered-os-fixture/usr/share/samba/smb.conf",
                "rendered-os-fixture/etc/passwd","rendered-os-fixture/etc/group","rendered-os-fixture/etc/shadow","rendered-os-fixture/etc/gshadow",
                "rendered-os-fixture/var/lib/samba/private/passdb.tdb","rendered-os-fixture/var/lib/samba/private/secrets.tdb",
                "rendered-os-fixture/var/lib/samba/winbindd_idmap.tdb","configuration/smb-managed-identities.json",
                "configuration/smb-local-account-provenance.json","configuration/smb-semantic-identity-aliases.json",
                "configuration/secrets/iscsi-acl-secrets.json","configuration/secrets/nvmeof-acl-secrets.json",
                "configuration/smb-domain.json","configuration/ad-machine.conf","rendered-os-fixture/etc/krb5.keytab")) {
            java.nio.file.Path path=nativeCase.resolve(name);JsonObject fingerprint=new JsonObject();
            boolean present=Files.exists(path,java.nio.file.LinkOption.NOFOLLOW_LINKS);fingerprint.addProperty("present",present);
            if(present) {
                Assert.assertFalse(Files.isSymbolicLink(path));Assert.assertTrue(path.toRealPath().startsWith(nativeCase.toRealPath()));
                Assert.assertTrue(Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS));
                java.util.Map<String,Object> attributes=Files.readAttributes(path,"unix:mode,uid,gid,nlink,size",java.nio.file.LinkOption.NOFOLLOW_LINKS);
                Assert.assertTrue(((Number)attributes.get("size")).longValue()<=8L*1024*1024);
                fingerprint.addProperty("sha256",StorageConfigArchive.sha256(Files.readAllBytes(path)));
                Assert.assertEquals(attributes,Files.readAttributes(path,"unix:mode,uid,gid,nlink,size",java.nio.file.LinkOption.NOFOLLOW_LINKS));
                for(String key:java.util.List.of("mode","uid","gid","nlink","size"))fingerprint.addProperty(key,(Number)attributes.get(key));
            }
            result.add(name,fingerprint);
        }
        return result;
    }
    void assertEmptySourceRuntime()throws Exception {
        JsonObject observed=generation("operation observe",new JsonObject());Assert.assertTrue(observed.get("success").getAsBoolean());
        JsonObject verified=observed.getAsJsonObject("realRenderedProtocolVerification");
        for(String domain:java.util.List.of("NFS","SMB","ISCSI","NVMEOF"))Assert.assertTrue(domain,verified.get(domain).getAsBoolean());
        Assert.assertEquals(0,observed.getAsJsonObject("nfsGanesha").get("active").getAsInt());
        Assert.assertFalse(observed.getAsJsonObject("nfsGanesha").get("listening").getAsBoolean());
        Assert.assertEquals(0,observed.getAsJsonArray("nfsGaneshaExports").size());
        Assert.assertEquals(0,observed.getAsJsonObject("smbRuntime").getAsJsonArray("runtimeEndpoints").size());
        Assert.assertEquals(0,observed.getAsJsonArray("smbShares").size());
        Assert.assertEquals(0,observed.getAsJsonObject("iscsiTargets").getAsJsonArray("targets").size());
        Assert.assertEquals(0,observed.getAsJsonObject("nvmeofSubsystems").getAsJsonArray("subsystems").size());
        Assert.assertFalse(observed.getAsJsonObject("nvmeofRuntime").get("listening").getAsBoolean());
        for(String name:java.util.List.of("proc","etc/ganesha/ablestack-storage","sys/kernel/config/target/iscsi",
                "sys/kernel/config/nvmet/subsystems","sys/kernel/config/nvmet/ports","sys/kernel/config/nvmet/hosts")) {
            java.nio.file.Path path=nativeCase.resolve("rendered-os-fixture").resolve(name);
            Assert.assertFalse(Files.isSymbolicLink(path));Assert.assertTrue(path.toRealPath().startsWith(nativeCase.toRealPath()));
            try(var children=Files.list(path)){Assert.assertEquals(name,0,children.count());}
        }
    }
    @Override JsonObject guest(String command,String body)throws Exception{
        if(command.equals("operation maintenance service-enter")&&rejectServiceEnterBeforeHoldOnce){
            rejectServiceEnterBeforeHoldOnce=false;throw new com.cloud.utils.exception.CloudRuntimeException("Fixture rejected formatter lock fence before SERVICE hold");
        }
        if(command.equals("operation generation rollback")&&loseNoHeldRollbackResponseOnce){
            generation(command,obj(body));loseNoHeldRollbackResponseOnce=false;
            throw new com.cloud.utils.exception.CloudRuntimeException("Fixture lost response after exact own native rollback");
        }
        if(command.equals("operation generation render-activate") && rejectAfterActivateOnce) {
            JsonObject applied=generation(command,obj(body));Assert.assertEquals("VERIFIED",applied.getAsJsonObject("activation").get("phase").getAsString());
            rejectAfterActivateOnce=false;throw new com.cloud.utils.exception.CloudRuntimeException("Fixture transport response lost after verified activation");
        }
        if(command.equals("operation generation render-stage") && rejectStageOnce) {
            rejectStageOnce=false;throw new com.cloud.utils.exception.CloudRuntimeException("Fixture transport rejected stage before activation");
        }
        if(command.startsWith("operation maintenance service-"))return generation(command,obj(body));
        if(command.equals("volume attach inspect")){
            JsonObject request=obj(body),reply=super.guest(command,body),config=request.has("config")?request.getAsJsonObject("config"):new JsonObject();
            String uuid=request.get("volumeUuid").getAsString(),mount="/srv/ablestack-storage/volumes/"+uuid;
            Assert.assertTrue(volumeRows.values().stream().anyMatch(v->uuid.equals(v.getUuid())));
            String mode=request.has("importMode")?request.get("importMode").getAsString():config.has("importMode")?config.get("importMode").getAsString():"MOUNT_EXISTING";
            Assert.assertEquals("MOUNT_EXISTING",mode);
            if(config.has("volumeMountPath"))Assert.assertEquals(mount,config.get("volumeMountPath").getAsString());
            if(request.has("expectedFilesystemUuid"))Assert.assertEquals(reply.get("filesystemUuid"),request.get("expectedFilesystemUuid"));
            String relative=config.has("relativeSharePath")?config.get("relativeSharePath").getAsString():"";if(!relative.isEmpty())relative=PosixDirectoryPolicy.relativePath(relative);
            reply.addProperty("volumeMountPath",mount);reply.addProperty("backingPath",mount+(relative.isEmpty()?"":"/"+relative));
            JsonObject service=Files.exists(nativeCase.resolve("volume-service-os-fixture.json"))?obj(Files.readString(nativeCase.resolve("volume-service-os-fixture.json"))):new JsonObject();
            JsonObject entry=reply.deepCopy();entry.remove("generatedEpoch");service.add(reply.get("backingPath").getAsString(),entry);
            Files.writeString(nativeCase.resolve("volume-service-os-fixture.json"),service.toString());return reply;
        }
        if(command.equals("operation verify")){
            JsonObject reply=generation(command,new JsonObject()),capabilities=new JsonObject();
            JsonObject signed=obj(new String(((StorageConfigSignedRuntimeFixture)runtimes).manifest,StandardCharsets.UTF_8));
            capabilities.addProperty("smbNetworkAccessSupported",StorageRuntimeFeatureCompatibility.advertised(signed).contains("SMB_NETWORK_ACL"));
            capabilities.addProperty("smbNetworkIpv6Supported",false);reply.add("capabilities",capabilities);return reply;
        }
        if(java.util.Set.of("operation observe","inventory","health","operation writer-idle","operation reservation status").contains(command))return generation(command,new JsonObject());
        if(command.equals("nfs idmapping preflight")){
            JsonObject requested=obj(body);Assert.assertEquals("NAME_DOMAIN",requested.get("idMappingMode").getAsString());
            return fresh(obj("{\"idMappingMode\":\"NAME_DOMAIN\"}"));
        }
        if(command.equals("operation generation render-service-capture-source")||command.equals("operation generation render-service-source-unheld-verify")||command.equals("operation generation render-service-identity-export-source"))return generation(command,obj(body));
        if(command.equals("identity capsule capabilities")||command.equals("identity capsule export-local-source")||command.equals("identity capsule export")||command.equals("smb identity local-source-status")||command.equals("operation generation render-status"))return generation(command,body==null||body.isBlank()?new JsonObject():obj(body));
        return super.guest(command,body);
    }
    org.apache.cloudstack.api.command.user.storage.dataservice.ApplyStorageServiceConfigRestoreCmd reviewedCommand()throws Exception{
        Method required=StorageServiceConfiguration.class.getDeclaredMethod("requiredCredentials",java.util.Map.class);required.setAccessible(true);
        plan.add("requiredCredentials",(JsonArray)required.invoke(configuration,StorageConfigArchive.validate(store.read(ARTIFACT,archiveSha))));
        StorageConfigArtifactVO artifact=artifactRows.get(900L);JsonObject metadata=obj(artifact.getMetadataJson());
        metadata.add("plan",plan.deepCopy());metadata.getAsJsonObject("planToken").addProperty("planSha256",sha(plan.toString()));metadata.getAsJsonObject("planToken").addProperty("baselineSha256",sha(manager.captureConfigurationSnapshot(7L)));
        artifact.setMetadataJson(metadata.toString());JsonObject original=obj(foundation.getPreviousSnapshotJson());
        original.addProperty("reviewedPlanSha256",StorageConfigurationVolumePlan.sha256(StorageConfigurationVolumePlan.canonical(plan)));
        foundation.setPreviousSnapshotJson(original.toString());operationRows.put(90L,copy(foundation,StorageServiceOperationVO.class));
        JsonObject credentials=new JsonObject();
        for(JsonElement e:plan.getAsJsonArray("requiredCredentials")){
            JsonObject rule=e.getAsJsonObject(),fields=new JsonObject();
            for(JsonElement f:rule.getAsJsonArray("fields"))fields.addProperty(f.getAsString(),"fixture-secret");
            credentials.add(rule.get("ruleUuid").getAsString(),fields);
        }
        transientCredentials=credentials.toString();
        JsonObject parameters=new JsonObject();parameters.addProperty("instanceid",7);parameters.addProperty("artifactid",900);
        parameters.addProperty("plantoken","fixture-review-token");parameters.addProperty("confirmation","retained-data-restore-fixture");parameters.addProperty("credentials",credentials.toString());
        var actual=(org.apache.cloudstack.api.command.user.storage.dataservice.ApplyStorageServiceConfigRestoreCmd)
                StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.ApplyStorageServiceConfigRestoreCmd.class,parameters);
        return actual;
    }

    void installNfsOnlyImport()throws Exception {
        var entries=new java.util.LinkedHashMap<>(StorageConfigArchive.validate(store.read(ARTIFACT,archiveSha)));
        JsonObject manifest=obj(new String(entries.remove("manifest.json"),StandardCharsets.UTF_8));entries.remove("SHA256SUMS");manifest.remove("checksums");
        java.util.Set<String> shares=new java.util.HashSet<>();
        for(String kind:java.util.List.of("protocols","file-shares")) {
            JsonArray filtered=new JsonArray();for(JsonElement item:com.google.gson.JsonParser.parseString(new String(entries.get("desired/"+kind+".json"),StandardCharsets.UTF_8)).getAsJsonArray())
                if("NFS".equals(item.getAsJsonObject().get("protocol").getAsString())){filtered.add(item);if("file-shares".equals(kind))shares.add(item.getAsJsonObject().get("uuid").getAsString());}
            entries.put("desired/"+kind+".json",filtered.toString().getBytes(StandardCharsets.UTF_8));
        }
        JsonArray acls=new JsonArray();for(JsonElement item:com.google.gson.JsonParser.parseString(new String(entries.get("desired/access-rules.json"),StandardCharsets.UTF_8)).getAsJsonArray())
            if(shares.contains(item.getAsJsonObject().get("resourceUuid").getAsString()))acls.add(item);
        entries.put("desired/access-rules.json",acls.toString().getBytes(StandardCharsets.UTF_8));
        for(String kind:java.util.List.of("block-targets","identity-domain"))entries.put("desired/"+kind+".json","[]".getBytes(StandardCharsets.UTF_8));
        byte[] archive=StorageConfigArchive.create(entries,manifest);String imported=id(1901);StorageConfigArtifactVO row=copy(artifactRows.get(900L),StorageConfigArtifactVO.class);
        set(row,"id",901L);set(row,"uuid",imported);row.setInstanceId(80);row.setState("ARCHIVE_VALIDATED");row.setMetadataJson("{}");row.setSha256(StorageConfigArchive.sha256(archive));row.setSize(archive.length);
        artifactRows.put(901L,row);store.write(imported,archive);
        Mockito.when(artifacts.lockRow(901L,true)).thenAnswer(c->copy(artifactRows.get(901L),StorageConfigArtifactVO.class));
        Mockito.when(artifacts.update(Mockito.eq(901L),Mockito.any())).thenAnswer(c->{artifactRows.put(901L,copy(c.getArgument(1),StorageConfigArtifactVO.class));return true;});
    }

    org.apache.cloudstack.api.command.admin.storage.dataservice.ApplyStorageServiceApprovedMaintenanceCmd existingApprovedCommand(String key)throws Exception {return existingApprovedCommand(key,false);}
    org.apache.cloudstack.api.command.admin.storage.dataservice.ApplyStorageServiceApprovedMaintenanceCmd activeApprovedCommand(String key)throws Exception {return existingApprovedCommand(key,true);}
    org.apache.cloudstack.api.command.admin.storage.dataservice.ApplyStorageServiceApprovedMaintenanceCmd existingApprovedCommand(String key,boolean active)throws Exception {
        String imported=id(1901);
        if(!artifactRows.containsKey(901L)){
            StorageConfigArtifactVO row=copy(artifactRows.get(900L),StorageConfigArtifactVO.class);set(row,"id",901L);set(row,"uuid",imported);
            row.setInstanceId(80);row.setState("ARCHIVE_VALIDATED");row.setMetadataJson("{}");artifactRows.put(901L,row);store.write(imported,store.read(ARTIFACT,archiveSha));
            Mockito.when(artifacts.lockRow(901L,true)).thenAnswer(c->copy(artifactRows.get(901L),StorageConfigArtifactVO.class));
            Mockito.when(artifacts.update(Mockito.eq(901L),Mockito.any())).thenAnswer(c->{artifactRows.put(901L,copy(c.getArgument(1),StorageConfigArtifactVO.class));return true;});
        }
        JsonObject mapping=new JsonObject(),volumes=new JsonObject(),listeners=new JsonObject();
        for(var item:sourceToTarget.entrySet())volumes.addProperty(item.getKey(),item.getValue());mapping.add("volumes",volumes);
        StorageConfigArtifactVO importedArtifact=artifactRows.get(901L);
        var sourceResources=StorageConfigRestorePlan.resources(StorageConfigArchive.validate(store.read(importedArtifact.getUuid(),importedArtifact.getSha256())));
        for(JsonElement item:sourceResources.get("protocols")) {
            JsonObject row=item.getAsJsonObject();JsonElement address=row.get("listen_ip");
            if(address!=null&&!address.isJsonNull())listeners.addProperty(address.getAsString(),"0.0.0.0".equals(address.getAsString())?"0.0.0.0":"PRIMARY");
        }
        mapping.add("listenerAddresses",listeners);
        JsonObject protocols=new JsonObject();
        if(active) {
            String snapshot=manager.captureConfigurationSnapshot(80L);
            var actual=StorageConfigRestorePlan.resources(StorageConfigSemantic.export(snapshot,manager.configurationInstanceMetadata(instanceRows.get(80L)),manager.configurationVolumeMetadata(instanceRows.get(80L),snapshot)));
            var importedRow=artifactRows.get(901L);var archived=StorageConfigRestorePlan.resources(StorageConfigArchive.validate(store.read(importedRow.getUuid(),importedRow.getSha256())));
            for(String kind:java.util.List.of("protocols","posix-directory-policies","file-shares","block-targets","access-rules","identity-domain")) {
                JsonObject selected=new JsonObject();
                for(JsonElement value:archived.get(kind)) {
                    JsonObject desired=value.getAsJsonObject();java.util.List<JsonObject> candidates=new java.util.ArrayList<>();
                    for(JsonElement entry:actual.get(kind)) {
                        JsonObject candidate=entry.getAsJsonObject();boolean matches=true;
                        String[] identity="protocols".equals(kind)?new String[]{"protocol","port"}:
                            "file-shares".equals(kind)?new String[]{"protocol","name"}:"block-targets".equals(kind)?new String[]{"protocol","target_name","lun_or_namespace"}:
                            "access-rules".equals(kind)?new String[]{"resource_type","principal_type","principal"}:"posix-directory-policies".equals(kind)?new String[]{"relative_path"}:new String[]{"domain_name"};
                        for(String field:identity)if(!java.util.Objects.equals(desired.get(field),candidate.get(field)))matches=false;
                        if("protocols".equals(kind)) {
                            JsonObject bound=desired.deepCopy();String address=bound.has("listen_ip")&&!bound.get("listen_ip").isJsonNull()?bound.get("listen_ip").getAsString():"0.0.0.0";
                            if(listeners.has(address)) {String selectedAddress=listeners.get(address).getAsString();bound.addProperty("listen_ip","PRIMARY".equals(selectedAddress)?manager.configurationPrimaryAddress(instanceRows.get(80L)):selectedAddress);}
                            if(!StorageConfigRestorePlan.protocolTuple(bound).equals(StorageConfigRestorePlan.protocolTuple(candidate)))matches=false;
                        }
                        if(desired.has("volumeUuid")) {
                            String targetVolume=sourceToTarget.get(desired.get("volumeUuid").getAsString());
                            if(targetVolume==null||!candidate.has("volumeUuid")||!targetVolume.equals(candidate.get("volumeUuid").getAsString()))matches=false;
                        }
                        if("access-rules".equals(kind)) {
                            String ownerKind="FILE_SHARE".equals(desired.get("resource_type").getAsString())?"file-shares":"block-targets";
                            JsonObject ownerMapping=mapping.getAsJsonObject(ownerKind);String sourceOwner=desired.get("resourceUuid").getAsString();
                            if(ownerMapping==null||!ownerMapping.has(sourceOwner)||!ownerMapping.get(sourceOwner).equals(candidate.get("resourceUuid"))
                                    ||!java.util.Objects.equals(desired.get("permission"),candidate.get("permission")))matches=false;
                        }
                        if("block-targets".equals(kind)&&!java.util.Objects.equals(desired.getAsJsonObject("config").get("type"),candidate.getAsJsonObject("config").get("type")))matches=false;
                        if(matches)candidates.add(candidate);
                    }
                    Assert.assertEquals(kind+" has no unique actual owned match",1,candidates.size());selected.addProperty(desired.get("uuid").getAsString(),candidates.get(0).get("uuid").getAsString());
                }
                mapping.add(kind,selected);if("protocols".equals(kind))protocols=selected;
            }
        } else {
        JsonObject currentProtocols=manager.configurationProtocolIntentSnapshot(instanceRows.get(80L));
        Assert.assertEquals(1,currentProtocols.size());String defaultUuid=currentProtocols.keySet().iterator().next();
        JsonObject constructor=currentProtocols.getAsJsonObject(defaultUuid);Assert.assertEquals("NFS",constructor.get("protocol").getAsString());
        Assert.assertEquals("0.0.0.0",constructor.get("listenIp").getAsString());Assert.assertEquals(2049,constructor.get("port").getAsInt());
        for(JsonElement entry:StorageConfigRestorePlan.resources(StorageConfigArchive.validate(store.read(ARTIFACT,archiveSha))).get("protocols")) {
            JsonObject source=entry.getAsJsonObject();
            if("NFS".equals(source.get("protocol").getAsString())&&(!source.has("listen_ip")||source.get("listen_ip").isJsonNull()||"0.0.0.0".equals(source.get("listen_ip").getAsString()))&&source.get("port").getAsInt()==2049)
                protocols.addProperty(source.get("uuid").getAsString(),defaultUuid);
        }
        Assert.assertEquals(1,protocols.size());mapping.add("protocols",protocols);
        }
        JsonObject parameters=new JsonObject();parameters.addProperty("instanceid",80);parameters.addProperty("artifactid",901);parameters.addProperty("targetmode","RESTORE_EXISTING");parameters.addProperty("mapping",mapping.toString());
        var review=(org.apache.cloudstack.api.command.user.storage.dataservice.PlanStorageServiceConfigRestoreCmd)StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.PlanStorageServiceConfigRestoreCmd.class,parameters);
        JsonObject result=obj(new com.google.gson.Gson().toJson(manager.storageServiceConfiguration(review))),publicPlan=obj(result.get("result").getAsString());
        Assert.assertTrue("Reviewed fixture plan must be executable: "+publicPlan,publicPlan.has("planToken"));String token=publicPlan.get("planToken").getAsString();
        JsonObject reviewed=obj(artifactRows.get(901L).getMetadataJson()).getAsJsonObject("plan"),credentials=new JsonObject();
        Assert.assertEquals("RESTORE_EXISTING",reviewed.get("targetMode").getAsString());Assert.assertEquals(0,reviewed.getAsJsonArray("blockers").size());
        Assert.assertEquals(protocols,reviewed.getAsJsonObject("resourceMappings").get("protocols"));
        if(!active)for(JsonElement entry:reviewed.getAsJsonArray("preserve"))Assert.assertNotEquals("protocols",entry.getAsJsonObject().get("kind").getAsString());
        for(JsonElement item:reviewed.getAsJsonArray("requiredCredentials")){
            JsonObject rule=item.getAsJsonObject(),values=new JsonObject();for(JsonElement field:rule.getAsJsonArray("fields"))values.addProperty(field.getAsString(),"fixture-secret");credentials.add(rule.get("ruleUuid").getAsString(),values);
        }
        JsonObject apply=new JsonObject();apply.addProperty("instanceid",80);apply.addProperty("artifactid",901);apply.addProperty("plantoken",token);
        apply.addProperty("confirmation",instanceRows.get(80L).getName());apply.addProperty("maintenancewindow",true);apply.addProperty("credentials",credentials.toString());apply.addProperty("idempotencykey",key);
        apply.addProperty("expectedrevision",reviewed.get("expectedRevision").getAsLong());
        return (org.apache.cloudstack.api.command.admin.storage.dataservice.ApplyStorageServiceApprovedMaintenanceCmd)StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.admin.storage.dataservice.ApplyStorageServiceApprovedMaintenanceCmd.class,apply);
    }

    org.apache.cloudstack.api.command.user.storage.dataservice.ApplyStorageServiceConfigRestoreCmd freshReviewCommand()throws Exception {
        JsonObject parameters=new JsonObject();parameters.addProperty("instanceid",7);parameters.addProperty("artifactid",900);
        parameters.addProperty("targetmode","CREATE_NEW");parameters.addProperty("mapping",recordedMapping.toString());
        var planCommand=(org.apache.cloudstack.api.command.user.storage.dataservice.PlanStorageServiceConfigRestoreCmd)
                StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.PlanStorageServiceConfigRestoreCmd.class,parameters);
        var response=manager.storageServiceConfiguration(planCommand);
        JsonObject publicResult=obj(new com.google.gson.Gson().toJson(response));
        String token=obj(publicResult.get("result").getAsString()).get("planToken").getAsString();
        JsonObject apply=new JsonObject();apply.addProperty("instanceid",7);apply.addProperty("artifactid",900);apply.addProperty("plantoken",token);
        apply.addProperty("confirmation","retained-data-restore-fixture");apply.addProperty("credentials",transientCredentials);
        return (org.apache.cloudstack.api.command.user.storage.dataservice.ApplyStorageServiceConfigRestoreCmd)
                StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.ApplyStorageServiceConfigRestoreCmd.class,apply);
    }
    }
}

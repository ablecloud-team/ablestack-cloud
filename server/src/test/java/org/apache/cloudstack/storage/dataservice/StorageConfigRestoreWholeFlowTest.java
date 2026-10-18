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

    @Test public void rejectedStageRestoresTheVerifiedEmptySourceAndReleasesOnlyItsOwnHold()throws Exception {
        fixture.rejectStageOnce=true;
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
        fixture.manager.storageServiceConfiguration(fixture.reviewedCommand());
        assertCompletedRestore(disks);
    }

    private void assertCompletedRestore(String disks)throws Exception {
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
        Assert.assertEquals("COMPLETE",fixture.obj(fixture.artifactRows.get(900L).getMetadataJson()).get("restoreState").getAsString());
        Assert.assertEquals(16,fixture.database.rows.values().stream().mapToInt(java.util.Map::size).sum());
        Assert.assertEquals(6,fixture.database.rows.get("storage_service_protocol").size());
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
        JsonObject listeners=new JsonObject();listeners.addProperty("10.10.13.223","PRIMARY");recordedMapping.add("listenerAddresses",listeners);
        StorageConfigArtifactVO artifact=artifactRows.get(900L);JsonObject metadata=obj(artifact.getMetadataJson()),requested=recordedMapping.deepCopy();
        requested.remove("listenerAddresses");metadata.add("requestedVolumeMapping",requested);metadata.add("plan",plan.deepCopy());artifact.setMetadataJson(metadata.toString());
        Mockito.doAnswer(call->{
            StorageServiceOperationVO value=call.getArgument(1);JsonObject prior=obj(value.getPreviousSnapshotJson()==null?"{}":value.getPreviousSnapshotJson());
            JsonObject rendered=prior.has("renderedGeneration")?prior.getAsJsonObject("renderedGeneration"):new JsonObject();
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
        if(java.util.Set.of("operation observe","inventory","health","operation writer-idle").contains(command))return generation(command,new JsonObject());
        if(command.equals("nfs idmapping preflight")){
            JsonObject requested=obj(body);Assert.assertEquals("NAME_DOMAIN",requested.get("idMappingMode").getAsString());
            return fresh(obj("{\"idMappingMode\":\"NAME_DOMAIN\"}"));
        }
        if(command.equals("identity capsule capabilities")||command.equals("identity capsule export-local-source")||command.equals("smb identity local-source-status")||command.equals("operation generation render-status"))return generation(command,body==null||body.isBlank()?new JsonObject():obj(body));
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

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

import java.util.List;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import org.junit.Assert;
import org.junit.Test;

public class StoragePreImportServiceSourceRecoveryTest {
    static class Fixture extends StorageUnchangedVerifiedSourceRecoveryTest.Fixture {
        Fixture()throws Exception {
            source.setRevision(14);original.setRevision(15);original.setProgress(0);
            original.setAction("SERVICE_MAINTENANCE");original.setRequestKey("SERVICE_MAINTENANCE:original-intent");
            original.setPhase("RECOVERY_REQUIRED");original.setDiagnostic("Original pre-import renderer failure");
            status.addProperty("runtimeRevision",14);status.getAsJsonObject("generation").addProperty("revision",14);
            frozen.getAsJsonObject("generation").addProperty("revision",14);
        }
        @Override JsonObject proof(){return StorageUnchangedVerifiedSourceRecovery.requirePreImportService(instance(),original,source,snapshot.toString(),bindings,status,frozen);}
        JsonObject importReceipt() {
            JsonObject receipt=new JsonObject(),scope=new JsonObject();receipt.addProperty("schemaVersion",1);receipt.addProperty("action","import");
            scope.addProperty("instanceUuid",instance());scope.addProperty("operationUuid",original.getUuid());scope.addProperty("revision",15);
            receipt.add("scope",scope);receipt.addProperty("diagnostic","runtimeSchemaVersion=1; protocol=ISCSI; phase=IMPORT; code=IMPORT_RENDER_FAILED");
            return receipt;
        }
        void diagnostic(JsonObject receipt){JsonObject result=StorageUnchangedVerifiedSourceRecoveryTest.object(original.getResultJson());result.add("_renderedFailureDiagnostic",receipt);original.setResultJson(result.toString());}
    }
    @Test public void adjacentCompleteSourceAuthorizesOnlySeparateServiceProofAndNeverBroadensNfsEligibility()throws Exception {
        Fixture f=new Fixture();JsonObject proof=f.proof();
        Assert.assertEquals("UNCHANGED_VERIFIED_PREIMPORT_SERVICE_SOURCE",proof.get("kind").getAsString());
        Assert.assertEquals(14,proof.get("sourceRevision").getAsLong());Assert.assertEquals(15,proof.get("revision").getAsLong());
        Assert.assertFalse(StorageUnchangedVerifiedSourceRecovery.eligible(f.original));
        Assert.assertThrows(CloudRuntimeException.class,()->StorageUnchangedVerifiedSourceRecovery.require(f.instance(),f.original,f.source,f.snapshot.toString(),f.bindings,f.status,f.frozen));
    }
    @Test public void checkpointsTargetEffectsProgressInitialCloneAndNonAdjacentSourcesRemainBlocked()throws Exception {
        for(int mode=0;mode<9;mode++) {
            Fixture f=new Fixture();
            if(mode==0)f.original.setProgress(1);
            if(mode==1)f.original.setRevision(2);
            if(mode==2)f.original.setAction("createstoragenfsexportresponse");
            if(mode==3)f.original.setSnapshotJson(f.snapshot.toString());
            if(mode==4)f.original.setRequestKey("unrelated");
            if(mode==5)f.source.setRevision(13);
            if(mode==6)f.original.setPhase("INTERRUPTED_RECOVERY_REQUIRED");
            if(mode==7){JsonObject previous=StorageUnchangedVerifiedSourceRecoveryTest.object(f.original.getPreviousSnapshotJson());previous.add("nativeGeneration",JsonNull.INSTANCE);f.original.setPreviousSnapshotJson(previous.toString());}
            if(mode==8){JsonObject result=StorageUnchangedVerifiedSourceRecoveryTest.object(f.original.getResultJson());result.add("_targetNativeBootId",f.status.get("bootId"));f.original.setResultJson(result.toString());}
            Assert.assertThrows(CloudRuntimeException.class,f::proof);
        }
    }
    @Test public void optionalImportReceiptIsClosedScopedAndNeverSubstitutesForUnchangedSourceEvidence()throws Exception {
        Fixture valid=new Fixture();valid.diagnostic(valid.importReceipt());valid.proof();
        for(int mode=0;mode<7;mode++) {
            Fixture f=new Fixture();JsonObject receipt=f.importReceipt();
            if(mode==0)receipt.addProperty("private","password=private");
            if(mode==1)receipt.getAsJsonObject("scope").addProperty("revision",14);
            if(mode==2)receipt.getAsJsonObject("scope").addProperty("operationUuid",f.source.getUuid());
            if(mode==3)receipt.addProperty("action","stage");
            if(mode==4)receipt.addProperty("diagnostic","runtimeSchemaVersion=1; protocol=ISCSI; phase=IMPORT; code=IMPORT_SOURCE_VERIFY_FAILED");
            if(mode==5)receipt.addProperty("diagnostic","runtimeSchemaVersion=1; protocol=ISCSI; phase=IMPORT; code=IMPORT_RENDER_FAILED; password=private");
            if(mode==6)receipt.addProperty("schemaVersion","1");
            f.diagnostic(receipt);Assert.assertThrows(CloudRuntimeException.class,f::proof);
        }
        Fixture pending=new Fixture();pending.diagnostic(pending.importReceipt());pending.status.addProperty("pendingOperationUuid",pending.original.getUuid());
        Assert.assertThrows(CloudRuntimeException.class,pending::proof);
        Fixture changed=new Fixture();changed.diagnostic(changed.importReceipt());changed.source.setSnapshotJson("{}");
        Assert.assertThrows(CloudRuntimeException.class,changed::proof);
    }
    @Test public void sourceActorSnapshotBindingsBootConfigFrozenAndOriginalFingerprintMustStayExact()throws Exception {
        for(int mode=0;mode<8;mode++) {
            Fixture f=new Fixture();
            if(mode==0)f.source.setCreatedBy(3);
            if(mode==1)f.source.setSnapshotJson("{}");
            if(mode==2)f.bindings.addProperty("rootVolumeUuid","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
            if(mode==3)f.status.addProperty("bootId","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
            if(mode==4)f.status.addProperty("configurationSha256","b".repeat(64));
            if(mode==5)f.frozen.getAsJsonObject("generation").addProperty("revision",13);
            if(mode==6)f.original.setResultJson("{}");
            if(mode==7)f.original.setResultJson("{\"_requestFingerprint\":true}");
            Assert.assertThrows(CloudRuntimeException.class,f::proof);
        }
    }
    @Test public void closedNfsValidatorReceiptAcceptsOnlyExactCodeStagePairAndStillRequiresSourceAbsenceProof()throws Exception {
        String[][] pairs={
            {"NFS_VALIDATOR_RUNTIME_UNAVAILABLE","RUNTIME_SELECTION"},{"NFS_VALIDATOR_RUNTIME_UNTRUSTED","RUNTIME_SELECTION"},
            {"NFS_VALIDATOR_NAMESPACE_FAILED","NAMESPACE_SETUP"},{"NFS_VALIDATOR_CHROOT_FAILED","CHROOT_SETUP"},
            {"NFS_VALIDATOR_PRIVATE_SERVICES_FAILED","PRIVATE_SERVICES"},{"NFS_VALIDATOR_PARSER_REJECTED","PARSER_STARTUP"},
            {"NFS_VALIDATOR_PARSER_TIMEOUT","PARSER_STARTUP"},{"NFS_VALIDATOR_WORKER_FAILED","WORKER_TRANSPORT"},{"NFS_VALIDATOR_WORKER_TIMEOUT","WORKER_TRANSPORT"}};
        for(String[] pair:pairs){
            Fixture f=new Fixture();JsonObject receipt=f.importReceipt();
            receipt.addProperty("diagnostic","protocol=NFS; stage="+pair[1]+"; runtimeValidationCode="+pair[0]);f.diagnostic(receipt);f.proof();
            for(String invalid:List.of("protocol=NFS; stage=UNKNOWN; runtimeValidationCode="+pair[0],
                    "protocol=SMB; stage="+pair[1]+"; runtimeValidationCode="+pair[0],
                    "protocol=NFS; stage="+pair[1]+"; runtimeValidationCode="+pair[0]+"; password=private",
                    "protocol=NFS; stage="+pair[1]+"; runtimeValidationCode=NFS_VALIDATOR_UNKNOWN")){
                Fixture bad=new Fixture();JsonObject forged=bad.importReceipt();forged.addProperty("diagnostic",invalid);bad.diagnostic(forged);
                Assert.assertThrows(CloudRuntimeException.class,bad::proof);
            }
        }
        Fixture mismatch=new Fixture();JsonObject forged=mismatch.importReceipt();
        forged.addProperty("diagnostic","protocol=NFS; stage=WORKER_TRANSPORT; runtimeValidationCode=NFS_VALIDATOR_PARSER_REJECTED");mismatch.diagnostic(forged);
        Assert.assertThrows(CloudRuntimeException.class,mismatch::proof);
        Fixture pending=new Fixture();JsonObject receipt=pending.importReceipt();
        receipt.addProperty("diagnostic","protocol=NFS; stage=PARSER_STARTUP; runtimeValidationCode=NFS_VALIDATOR_PARSER_REJECTED");pending.diagnostic(receipt);
        pending.status.addProperty("pendingOperationUuid",pending.original.getUuid());Assert.assertThrows(CloudRuntimeException.class,pending::proof);
    }

    @Test public void allClosedNonStageRuntimeReceiptsMatchRootImportContractAndRejectForgedPairs()throws Exception {
        String[][] pairs={
            {"NATIVE_CHILD_TIMEOUT","TRANSPORT"},{"NATIVE_CHILD_OUTPUT_INVALID","TRANSPORT"},{"NATIVE_CHILD_FAILED","TRANSPORT"},{"NATIVE_CHILD_INPUT_UNAVAILABLE","TRANSPORT"},
            {"RUNTIME_PREPARE_FAILED","PREPARE"},{"RUNTIME_APPLY_FAILED","APPLY"},{"RUNTIME_VERIFY_FAILED","VERIFY"},{"RUNTIME_ROLLBACK_FAILED","ROLLBACK"},
            {"ENDPOINT_LISTENER_ADDRESS_MISMATCH","VERIFY"},{"NFS_ALIAS_UNMOUNT_FAILED","CLEANUP"},{"NFS_ALIAS_UNMOUNT_UNVERIFIED","CLEANUP"},
            {"IMPORT_INPUT_INVALID","IMPORT"},{"IMPORT_CREDENTIAL_BINDING_FAILED","IMPORT"},{"IMPORT_RENDER_FAILED","IMPORT"},{"IMPORT_SOURCE_SCOPE_MISMATCH","IMPORT"},
            {"IMPORT_CANDIDATE_VALIDATION_FAILED","IMPORT"},{"IMPORT_SOURCE_VERIFY_FAILED","IMPORT"},{"IMPORT_PUBLICATION_FAILED","IMPORT"}};
        for(String[] pair:pairs) {
            String protocol=("NATIVE_CHILD_INPUT_UNAVAILABLE".equals(pair[0])||"RUNTIME_PREPARE_FAILED".equals(pair[0])
                    ||pair[0].startsWith("IMPORT_")&&!"IMPORT_RENDER_FAILED".equals(pair[0]))?"GENERATION":"NFS";
            Fixture f=new Fixture();JsonObject receipt=f.importReceipt();
            receipt.addProperty("diagnostic","runtimeSchemaVersion=1; protocol="+protocol+"; phase="+pair[1]+"; code="+pair[0]);f.diagnostic(receipt);f.proof();
            for(String fixed:List.of("runtimeSchemaVersion=1; protocol="+protocol+"; phase=UNKNOWN; code="+pair[0],
                    "runtimeSchemaVersion=1; protocol="+protocol+"; phase="+pair[1]+"; code="+pair[0]+"_PRIVATE",
                    "runtimeSchemaVersion=1; protocol=UNKNOWN; phase="+pair[1]+"; code="+pair[0],
                    "runtimeSchemaVersion=1; protocol="+protocol+"; phase="+pair[1]+"; code="+pair[0]+"; secret=private")){
                Fixture bad=new Fixture();JsonObject forged=bad.importReceipt();forged.addProperty("diagnostic",fixed);bad.diagnostic(forged);
                Assert.assertThrows(CloudRuntimeException.class,bad::proof);
            }
        }
        for(String invalid:List.of("runtimeSchemaVersion=1; protocol=SMB; phase=TRANSPORT; code=NATIVE_CHILD_INPUT_UNAVAILABLE",
                "runtimeSchemaVersion=1; protocol=ISCSI; phase=PREPARE; code=RUNTIME_PREPARE_FAILED",
                "runtimeSchemaVersion=1; protocol=SMB; phase=CLEANUP; code=NFS_ALIAS_UNMOUNT_FAILED",
                "runtimeSchemaVersion=1; protocol=SMB; phase=VERIFY; code=ENDPOINT_LISTENER_ADDRESS_MISMATCH",
                "runtimeSchemaVersion=1; protocol=GENERATION; phase=STAGE; code=STAGE_RENDER_FAILED")){
            Fixture f=new Fixture();JsonObject receipt=f.importReceipt();receipt.addProperty("diagnostic",invalid);f.diagnostic(receipt);
            Assert.assertThrows(CloudRuntimeException.class,f::proof);
        }
    }

}

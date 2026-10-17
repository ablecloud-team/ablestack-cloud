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
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;

public class StorageVolumePreparationProofTest {
    static JsonObject current() {
        JsonObject value = new JsonObject();
        value.addProperty("volumeUuid", "same-volume");value.addProperty("filesystemUuid", "same-filesystem");
        value.addProperty("filesystem", "xfs");value.addProperty("serial", "same-volume-serial");
        value.addProperty("sizeBytes", 1024);value.addProperty("mappingStatus", "EXACT");value.addProperty("matchedBy", "VOLUME_SERIAL");
        return value;
    }
    static JsonObject history() {
        JsonObject value = new JsonObject();value.addProperty("success", true);value.addProperty("formatterActive", false);value.addProperty("formatterObservationComplete",true);value.addProperty("terminationPending",false);
        JsonObject operation = new JsonObject();operation.addProperty("formatStarted", true);operation.addProperty("phase", "FILESYSTEM_VERIFIED");
        operation.addProperty("volumeUuid", "same-volume");operation.addProperty("filesystemUuid", "same-filesystem");operation.addProperty("filesystem", "xfs");operation.addProperty("formatterExitCode", 0);
        JsonObject receipt = current();receipt.addProperty("schemaVersion", 2);receipt.addProperty("formatterExitCode", 0);receipt.addProperty("verifiedEpoch", 123.456);
        JsonObject owner=new JsonObject();owner.addProperty("instanceUuid","11111111-1111-4111-8111-111111111111");
        owner.addProperty("managerOperationUuid","22222222-2222-4222-8222-222222222222");owner.addProperty("revision",8);
        operation.add("originalFormatOwner",owner);receipt.add("originalFormatOwner",owner.deepCopy());
        operation.addProperty("terminationPending",false);operation.addProperty("volumeSizeBytes",1024);
        operation.addProperty("formatterPid",41);receipt.addProperty("formatterPid",41);
        operation.addProperty("formatterStartTicks",12345);receipt.addProperty("formatterStartTicks",12345);
        operation.addProperty("formatterBootId","33333333-3333-4333-8333-333333333333");receipt.add("formatterBootId",operation.get("formatterBootId"));
        operation.addProperty("formatDiscardPolicy","SKIP_DISCARD");receipt.addProperty("formatDiscardPolicy","SKIP_DISCARD");
        com.google.gson.JsonArray options=new com.google.gson.JsonArray();options.add("-f");options.add("-K");
        operation.add("formatterOptions",options);receipt.add("formatterOptions",options.deepCopy());
        operation.add("formatterSuccessReceipt", receipt);value.add("operation", operation);return value;
    }
    @Test public void durableSuccessWithFreshSerialAndFilesystemAllowsForwardRecovery() {
        StorageVolumePreparationProof.requireCompleted(history(),current());
        JsonObject value=history();StorageVolumePreparationProof.project(value,current());
        Assert.assertTrue(value.get("filesystemCompletionProven").getAsBoolean());Assert.assertTrue(value.get("resumeAllowed").getAsBoolean());
    }
    @Test public void headerAndUuidWithoutSuccessfulMkfsReceiptRemainRecoveryRequired() {
        JsonObject value=history();value.getAsJsonObject("operation").remove("formatterSuccessReceipt");
        value.getAsJsonObject("operation").addProperty("phase","FORMATTING");
        Assert.assertThrows(CloudRuntimeException.class,()->StorageVolumePreparationProof.requireCompleted(value,current()));
        StorageVolumePreparationProof.project(value,current());Assert.assertFalse(value.get("resumeAllowed").getAsBoolean());
    }
    @Test public void activeTerminatingAndUnknownFormatterCannotApproveAnExistingSignature() {
        JsonObject value=history();value.addProperty("formatterActive",true);Assert.assertFalse(StorageVolumePreparationProof.completionProven(value,current()));
        value.addProperty("formatterActive",false);value.addProperty("terminationPending",true);Assert.assertFalse(StorageVolumePreparationProof.completionProven(value,current()));
        value.remove("terminationPending");value.remove("formatterActive");Assert.assertFalse(StorageVolumePreparationProof.completionProven(value,current()));
    }
    @Test public void foreignDiskReceiptChangedSerialAndFailedExitCannotApproveCompletion() {
        JsonObject value=history();value.getAsJsonObject("operation").getAsJsonObject("formatterSuccessReceipt").addProperty("volumeUuid","foreign-volume");Assert.assertFalse(StorageVolumePreparationProof.completionProven(value,current()));
        value=history();value.getAsJsonObject("operation").getAsJsonObject("formatterSuccessReceipt").addProperty("serial","foreign-serial");Assert.assertFalse(StorageVolumePreparationProof.completionProven(value,current()));
        value=history();value.getAsJsonObject("operation").addProperty("formatterExitCode",-9);Assert.assertFalse(StorageVolumePreparationProof.completionProven(value,current()));
        Assert.assertFalse(StorageVolumePreparationProof.completionProven(history(),null));
    }
    @Test public void weakLegacyReceiptAndUnobservedFormatterCannotApproveForwardMutation() {
        for(int mode=0;mode<7;mode++){
            JsonObject value=history(),op=value.getAsJsonObject("operation"),receipt=op.getAsJsonObject("formatterSuccessReceipt");
            if(mode==0)receipt.addProperty("schemaVersion",1);
            if(mode==1)receipt.remove("originalFormatOwner");
            if(mode==2)value.remove("formatterObservationComplete");
            if(mode==3)value.addProperty("formatterActive","false");
            if(mode==4)value.add("formatterActive",com.google.gson.JsonNull.INSTANCE);
            if(mode==5)op.addProperty("formatterStartTicks",12346);
            if(mode==6)op.addProperty("formatterBootId","44444444-4444-4444-8444-444444444444");
            Assert.assertFalse("mode "+mode,StorageVolumePreparationProof.completionProven(value,current()));
        }
    }
    @Test public void absentSerialOrEqualNonpositiveSizesNeverBecomeFilesystemProof() {
        for(int mode=0;mode<4;mode++){
            JsonObject value=history(),actual=current(),receipt=value.getAsJsonObject("operation").getAsJsonObject("formatterSuccessReceipt");
            if(mode==0){actual.remove("serial");receipt.remove("serial");}
            if(mode==1){actual.addProperty("serial","");receipt.addProperty("serial","");}
            if(mode>=2){actual.addProperty("sizeBytes",mode==2?0:-1);receipt.add("sizeBytes",actual.get("sizeBytes"));}
            Assert.assertFalse(StorageVolumePreparationProof.completionProven(value,actual));
        }
    }
    @Test public void changedOwnerOptionsOrFractionalIdentityIsRejected() {
        for(int mode=0;mode<5;mode++){
            JsonObject value=history(),receipt=value.getAsJsonObject("operation").getAsJsonObject("formatterSuccessReceipt");
            if(mode==0)receipt.getAsJsonObject("originalFormatOwner").addProperty("revision",9);
            if(mode==1)receipt.getAsJsonArray("formatterOptions").add("--foreign");
            if(mode==2)receipt.addProperty("sizeBytes",1024.5);
            if(mode==3)receipt.addProperty("sizeBytes",new java.math.BigInteger("18446744073709552640"));
            if(mode==4)receipt.addProperty("formatterExitCode","0");
            Assert.assertFalse(StorageVolumePreparationProof.completionProven(value,current()));
        }
    }
    private static JsonObject verifyingFilesystemCurrent() {
        JsonObject value = current();
        value.addProperty("volumeUuid", "35457e8b-6e03-46af-88a9-0eaffce2a595");
        value.addProperty("filesystemUuid", "8ea92265-7639-445d-8ec3-51dd160db097");
        value.addProperty("filesystem", "ext4");value.addProperty("serial", "35457e8b6e0346af88a9");
        value.addProperty("sizeBytes", 21474836480L);return value;
    }
    private static JsonObject verifyingFilesystemHistory() {
        // Public failed-remount observation: successful mkfs receipt survived the COMPLETE -> VERIFYING_FILESYSTEM transition.
        JsonObject value = history(), op = value.getAsJsonObject("operation"), receipt = op.getAsJsonObject("formatterSuccessReceipt");
        JsonObject actual = verifyingFilesystemCurrent();
        for (String field : new String[] {"volumeUuid", "filesystemUuid", "filesystem"}) {
            op.add(field, actual.get(field).deepCopy());receipt.add(field, actual.get(field).deepCopy());
        }
        op.addProperty("phase", "VERIFYING_FILESYSTEM");op.add("volumeSizeBytes", actual.get("sizeBytes").deepCopy());
        receipt.add("sizeBytes", actual.get("sizeBytes").deepCopy());receipt.add("serial", actual.get("serial").deepCopy());
        op.addProperty("formatterPid", 28183);receipt.add("formatterPid", op.get("formatterPid").deepCopy());
        op.addProperty("formatterStartTicks", 247132);receipt.add("formatterStartTicks", op.get("formatterStartTicks").deepCopy());
        op.addProperty("formatterBootId", "629dc32a-e8a5-43b2-ac0b-a49a0270519d");receipt.add("formatterBootId", op.get("formatterBootId").deepCopy());
        JsonObject owner = op.getAsJsonObject("originalFormatOwner");
        owner.addProperty("instanceUuid", "a249c3d4-2ddd-4540-8e30-a868763816cb");
        owner.addProperty("managerOperationUuid", "186f8fc2-334b-499a-ac35-c76b6cdcfc49");owner.addProperty("revision", 1);
        receipt.add("originalFormatOwner", owner.deepCopy());
        com.google.gson.JsonArray options = new com.google.gson.JsonArray();
        options.add("-F");options.add("-E");options.add("nodiscard,lazy_itable_init=1,lazy_journal_init=1");
        op.add("formatterOptions", options);receipt.add("formatterOptions", options.deepCopy());return value;
    }
    @Test public void successfulFormatReceiptSurvivesExistingFilesystemReverification() {
        JsonObject value = verifyingFilesystemHistory();
        StorageVolumePreparationProof.requireCompleted(value, verifyingFilesystemCurrent());
        StorageVolumePreparationProof.project(value, verifyingFilesystemCurrent());
        Assert.assertTrue(value.get("filesystemCompletionProven").getAsBoolean());
        Assert.assertTrue(value.get("resumeAllowed").getAsBoolean());
    }
    @Test public void reverificationPhaseWithoutCompleteSuccessfulReceiptCannotAdoptAHeader() {
        for (int mode = 0; mode < 3; mode++) {
            JsonObject value = verifyingFilesystemHistory(), op = value.getAsJsonObject("operation");
            if (mode == 0) op.remove("formatterSuccessReceipt");
            if (mode == 1) op.getAsJsonObject("formatterSuccessReceipt").remove("formatterExitCode");
            if (mode == 2) op.getAsJsonObject("formatterSuccessReceipt").addProperty("schemaVersion", 1);
            Assert.assertThrows(CloudRuntimeException.class,
                    () -> StorageVolumePreparationProof.requireCompleted(value, verifyingFilesystemCurrent()));
        }
    }
    @Test public void reverificationStillRejectsActiveFailedAndForeignFormatterProofs() {
        for (int mode = 0; mode < 4; mode++) {
            JsonObject value = verifyingFilesystemHistory(), op = value.getAsJsonObject("operation");
            if (mode == 0) value.addProperty("formatterActive", true);
            if (mode == 1) op.addProperty("formatterExitCode", 1);
            if (mode == 2) op.getAsJsonObject("formatterSuccessReceipt").addProperty("serial", "foreign-volume-serial");
            if (mode == 3) op.getAsJsonObject("formatterSuccessReceipt").getAsJsonObject("originalFormatOwner").addProperty("revision", 2);
            Assert.assertFalse("mode " + mode,
                    StorageVolumePreparationProof.completionProven(value, verifyingFilesystemCurrent()));
        }
    }
    @Test public void completedDefaultReceiptOnlyAuthorizesMountOnlyResumeWithItsExactOptions() {
        JsonObject value=history(),op=value.getAsJsonObject("operation"),receipt=op.getAsJsonObject("formatterSuccessReceipt");
        op.addProperty("formatDiscardPolicy","DEFAULT");receipt.addProperty("formatDiscardPolicy","DEFAULT");
        com.google.gson.JsonArray options=new com.google.gson.JsonArray();options.add("-f");op.add("formatterOptions",options);receipt.add("formatterOptions",options.deepCopy());
        Assert.assertTrue(StorageVolumePreparationProof.completionProven(value,current()));
        options.add("--unknown");receipt.add("formatterOptions",options.deepCopy());Assert.assertFalse(StorageVolumePreparationProof.completionProven(value,current()));
    }
}

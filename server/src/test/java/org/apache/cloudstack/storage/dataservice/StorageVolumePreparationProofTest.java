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
    private JsonObject current() {
        JsonObject value = new JsonObject();
        value.addProperty("volumeUuid", "same-volume");value.addProperty("filesystemUuid", "same-filesystem");
        value.addProperty("filesystem", "xfs");value.addProperty("serial", "same-volume-serial");
        value.addProperty("sizeBytes", 1024);value.addProperty("mappingStatus", "EXACT");value.addProperty("matchedBy", "VOLUME_SERIAL");
        return value;
    }
    private JsonObject history() {
        JsonObject value = new JsonObject();value.addProperty("success", true);value.addProperty("formatterActive", false);
        JsonObject operation = new JsonObject();operation.addProperty("formatStarted", true);operation.addProperty("phase", "FILESYSTEM_VERIFIED");
        operation.addProperty("volumeUuid", "same-volume");operation.addProperty("filesystemUuid", "same-filesystem");operation.addProperty("filesystem", "xfs");operation.addProperty("formatterExitCode", 0);
        JsonObject receipt = current();receipt.addProperty("schemaVersion", 1);receipt.addProperty("formatterExitCode", 0);receipt.addProperty("verifiedEpoch", 123.456);
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
}

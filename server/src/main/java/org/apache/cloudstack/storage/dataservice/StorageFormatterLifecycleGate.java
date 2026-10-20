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
/** Native lease absence cannot prove an older orphan formatter or its partial filesystem is safe. */
public final class StorageFormatterLifecycleGate {
    private StorageFormatterLifecycleGate() { }
    public static void requireIdle(JsonObject status) {
        if(status==null||!flag(status,"success",true))throw blocked();
        String phase=text(status,"status");
        if(flag(status,"formatterActive",false)||flag(status,"terminationPending",false))throw blocked();
        if(status.has("formatterObservationComplete")&&!flag(status,"formatterObservationComplete",true))throw blocked();
        JsonObject journal=status.has("operation")&&status.get("operation").isJsonObject()?status.getAsJsonObject("operation"):null;
        if("NOT_STARTED".equals(phase)) {
            if(journal!=null&&(flag(journal,"formatStarted",false)||flag(journal,"terminationPending",false)))throw blocked();
            return; // The legacy no-journal response has no formatter fields.
        }
        if(!status.has("formatterActive")||journal==null||flag(journal,"terminationPending",false))throw blocked();
        String recorded=text(journal,"phase");
        if(!journal.has("formatStarted")) {
            if("COMPLETE".equals(phase)&&"COMPLETE".equals(recorded)&&legacyExistingMountCompleted(status,journal))return;
            throw blocked();
        }
        boolean started=flag(journal,"formatStarted",true);
        if(!phase.equals(recorded)||started&&!"COMPLETE".equals(recorded)
                ||!started&&!java.util.Set.of("WAITING_DEVICE","VERIFYING_IDENTITY","RECOVERY_REQUIRED","COMPLETE").contains(recorded))throw blocked();
        if(started&&journal.has("formatterObservationComplete")&&!flag(journal,"formatterObservationComplete",true))throw blocked();
    }
    private static boolean legacyExistingMountCompleted(JsonObject status,JsonObject journal) {
        // Older ordinary MOUNT_EXISTING never ran mkfs and did not write formatStarted.
        // This permits the existing healthy lifecycle only; it is not a forward-resume receipt.
        if(!status.has("terminationPending")||flag(status,"terminationPending",true))return false;
        for(String key:new String[]{"formatterPid","formatterStartTicks","formatterBootId","formatterSuccessReceipt",
                "formatterExitCode","formatter","formatterOptions","formatDiscardPolicy","formatReceiptUuid","formatReceipt","originalFormatOwner",
                "formatterIdentity","formatterActive","formatterObservationComplete"})
            if(journal.has(key))return false;
        if(status.has("formatInvoked")&&flag(status,"formatInvoked",true)||journal.has("formatInvoked")&&flag(journal,"formatInvoked",true))return false;
        String volume=text(journal,"volumeUuid");
        if(!java.util.UUID.fromString(volume).toString().equals(volume)||!("volume-"+volume).equals(text(journal,"operationId"))
                ||!("/srv/ablestack-storage/volumes/"+volume).equals(text(journal,"mountPath"))
                ||!java.util.Set.of("xfs","ext4").contains(text(journal,"filesystem")))return false;
        return !text(journal,"filesystemUuid").isBlank();
    }
    private static boolean flag(JsonObject value,String key,boolean required) {
        com.google.gson.JsonElement item=value.get(key);
        if(item==null&&!required)return false;
        if(item==null||!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isBoolean())throw blocked();
        return item.getAsBoolean();
    }
    private static String text(JsonObject value,String key) {
        com.google.gson.JsonElement item=value.get(key);
        if(item==null||!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isString())throw blocked();
        return item.getAsString();
    }
    private static CloudRuntimeException blocked() {
        return new CloudRuntimeException("Formatter state is active, incomplete or unobserved; preserve VM/DATA for explicit forward recovery");
    }
}

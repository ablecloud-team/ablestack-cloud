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

import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** A filesystem header cannot attest successful formatting or authorize a forward mount. */
public final class StorageVolumePreparationProof {
    private StorageVolumePreparationProof() { }

    public static void project(JsonObject history, JsonObject current) {
        boolean complete = completionProven(history, current);
        history.addProperty("filesystemCompletionProven", complete);
        history.addProperty("resumeAllowed", complete);
        if (!complete) history.addProperty("resumeBlocker", "FORMATTER_COMPLETION_OR_CURRENT_IDENTITY_UNVERIFIED");
        else history.remove("resumeBlocker");
    }

    public static void requireCompleted(JsonObject history, JsonObject current) {
        if (!completionProven(history, current)) throw new CloudRuntimeException("Forward resume requires a durable successful formatter receipt and matching current serial/filesystem; partial DATA remains untouched");
    }

    public static boolean completionProven(JsonObject history, JsonObject current) {
        try {
            if(history==null||current==null||!flag(history,"success")||flag(history,"formatterActive")
                    ||!flag(history,"formatterObservationComplete")||flag(history,"terminationPending")
                    ||!"EXACT".equals(text(current,"mappingStatus"))||!"VOLUME_SERIAL".equals(text(current,"matchedBy")))return false;
            JsonObject operation=object(history,"operation"),receipt=object(operation,"formatterSuccessReceipt");
            if(operation==null||receipt==null||!flag(operation,"formatStarted")||flag(operation,"terminationPending")
                    ||number(receipt,"schemaVersion")!=2||number(receipt,"formatterExitCode")!=0||number(operation,"formatterExitCode")!=0
                    ||!positiveEpoch(receipt,"verifiedEpoch")||!Set.of("xfs","ext4").contains(text(current,"filesystem"))
                    ||!Set.of("FILESYSTEM_VERIFIED","MOUNTING","PERSISTING_MOUNT","COMPLETE","RECOVERY_REQUIRED",
                        "RECONCILE_REQUIRED","TIMED_OUT_PENDING_RECONCILE").contains(text(operation,"phase")))return false;
            for(String field:new String[]{"volumeUuid","filesystemUuid","filesystem"}) {
                if(!text(current,field).equals(text(operation,field))||!text(current,field).equals(text(receipt,field)))return false;
            }
            if(!text(current,"serial").equals(text(receipt,"serial"))||number(current,"sizeBytes")<=0
                    ||number(current,"sizeBytes")!=number(receipt,"sizeBytes")||number(operation,"volumeSizeBytes")!=number(current,"sizeBytes")
                    ||number(receipt,"formatterPid")<=0||number(receipt,"formatterPid")!=number(operation,"formatterPid")
                    ||number(receipt,"formatterStartTicks")<=0||number(receipt,"formatterStartTicks")!=number(operation,"formatterStartTicks")
                    ||!uuid(receipt,"formatterBootId").equals(uuid(operation,"formatterBootId"))
                    ||!Set.of("DEFAULT","SKIP_DISCARD").contains(text(receipt,"formatDiscardPolicy"))
                    ||!text(receipt,"formatDiscardPolicy").equals(text(operation,"formatDiscardPolicy")))return false;
            JsonObject owner=object(operation,"originalFormatOwner");
            if(owner==null||!owner.equals(object(receipt,"originalFormatOwner"))||number(owner,"revision")<=0)return false;
            uuid(owner,"instanceUuid");uuid(owner,"managerOperationUuid");
            com.google.gson.JsonElement options=receipt.get("formatterOptions");
            if(options==null||!options.isJsonArray()||options.getAsJsonArray().size()==0||options.getAsJsonArray().size()>8
                    ||!options.equals(operation.get("formatterOptions")))return false;
            for(com.google.gson.JsonElement option:options.getAsJsonArray())
                if(!option.isJsonPrimitive()||!option.getAsJsonPrimitive().isString()||option.getAsString().isEmpty()||option.getAsString().length()>128)return false;
            java.util.List<String> expected="xfs".equals(text(current,"filesystem"))?java.util.List.of("-f"):java.util.List.of("-F");
            if("SKIP_DISCARD".equals(text(receipt,"formatDiscardPolicy")))
                expected="xfs".equals(text(current,"filesystem"))?java.util.List.of("-f","-K")
                        :java.util.List.of("-F","-E","nodiscard,lazy_itable_init=1,lazy_journal_init=1");
            if(options.getAsJsonArray().size()!=expected.size())return false;
            for(int i=0;i<expected.size();i++)if(!expected.get(i).equals(options.getAsJsonArray().get(i).getAsString()))return false;
            return true;
        } catch(RuntimeException malformed) {return false;}
    }

    private static JsonObject object(JsonObject value,String field) {
        return value!=null&&value.has(field)&&value.get(field).isJsonObject()?value.getAsJsonObject(field):null;
    }
    private static boolean flag(JsonObject value,String field) {
        JsonElement element=value==null?null:value.get(field);
        if(element==null||!element.isJsonPrimitive()||!element.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("Unobserved formatter flag");
        return element.getAsBoolean();
    }
    private static boolean positiveEpoch(JsonObject value,String field) {
        JsonElement element=value.get(field);
        return element!=null&&element.isJsonPrimitive()&&element.getAsJsonPrimitive().isNumber()&&element.getAsBigDecimal().signum()>0;
    }
    private static long number(JsonObject value,String field) {
        JsonElement element=value==null?null:value.get(field);
        if(element==null||!element.isJsonPrimitive()||!element.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Missing formatter proof number");
        return element.getAsBigDecimal().longValueExact();
    }
    private static String text(JsonObject value,String field) {
        JsonElement element=value==null?null:value.get(field);
        if(element==null||!element.isJsonPrimitive()||!element.getAsJsonPrimitive().isString()||element.getAsString().isBlank())throw new IllegalArgumentException("Missing formatter proof identity");
        return element.getAsString();
    }
    private static String uuid(JsonObject value,String field) {
        String result=text(value,field);
        if(!java.util.UUID.fromString(result).toString().equals(result))throw new IllegalArgumentException("Malformed formatter proof UUID");
        return result;
    }
}

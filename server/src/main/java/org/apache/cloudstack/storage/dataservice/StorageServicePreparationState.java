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
import com.google.gson.JsonObject;
/** Current-BOOT startup readiness. No listeners or existing generation are required on a fresh VM. */
public final class StorageServicePreparationState {
    private StorageServicePreparationState() { }
    public static final class Result {
        public final boolean ready; public final String status,reason,bootId;
        Result(boolean ready,String status,String reason,String bootId){this.ready=ready;this.status=status;this.reason=reason;this.bootId=bootId;}
    }
    private static Result no(String status,String reason,String boot){return new Result(false,status,reason,boot);}
    private static String text(JsonObject v,String k){return v!=null&&v.has(k)&&v.get(k).isJsonPrimitive()&&v.get(k).getAsJsonPrimitive().isString()?v.get(k).getAsString():null;}
    private static boolean flag(JsonObject v,String k,boolean b){return v!=null&&v.has(k)&&v.get(k).isJsonPrimitive()&&v.get(k).getAsJsonPrimitive().isBoolean()&&v.get(k).getAsBoolean()==b;}
    private static JsonObject object(JsonObject v,String k){return v!=null&&v.has(k)&&v.get(k).isJsonObject()?v.getAsJsonObject(k):null;}
    private static boolean fresh(JsonObject v,String key,long now) {
        try {if(v==null||!v.has(key)||!v.get(key).isJsonPrimitive()||!v.get(key).getAsJsonPrimitive().isNumber())return false;double epoch=v.get(key).getAsDouble();return Double.isFinite(epoch)&&now/1000.0-epoch>=-5&&now/1000.0-epoch<=30;}catch(RuntimeException invalid){return false;}
    }
    private static boolean nullableString(JsonObject v,String k) {
        return v!=null&&v.has(k)&&(v.get(k).isJsonNull()||v.get(k).isJsonPrimitive()&&v.get(k).getAsJsonPrimitive().isString());
    }
    private static boolean booleanField(JsonObject v,String k){return v!=null&&v.has(k)&&v.get(k).isJsonPrimitive()&&v.get(k).getAsJsonPrimitive().isBoolean();}
    public static boolean databaseRecoveryRequired(String state,long revision,long committed) {
        return revision>committed&&state!=null&&java.util.Set.of("RECOVERY_REQUIRED","BLOCKED","APPLY_FAILED").contains(state);
    }
    public static Result evaluate(boolean vmRunning,boolean databaseIdle,boolean formatterIdle,
            JsonObject health,JsonObject before,JsonObject after,JsonObject idle,JsonObject maintenance,long now) {
        if(!vmRunning)return no("WAITING_VM","VM_NOT_RUNNING",null);
        String boot=text(before,"bootId");
        if(boot==null||!boot.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            return no("OBSERVATION_UNAVAILABLE","CURRENT_BOOT_UNOBSERVED",null);
        if(!flag(before,"success",true)||!flag(before,"generationSupported",true)||!flag(after,"success",true)||!flag(after,"generationSupported",true)
                ||!nullableString(before,"pendingOperationUuid")||!nullableString(after,"pendingOperationUuid"))
            return no("OBSERVATION_UNAVAILABLE","GENERATION_OBSERVATION_INVALID",boot);
        if(!boot.equals(text(after,"bootId"))||!boot.equals(text(health,"bootId")))
            return no("OBSERVATION_UNAVAILABLE","BOOT_CHANGED_OR_STALE",boot);
        if(!fresh(health,"generatedEpoch",now)||health.has("stale")&&!booleanField(health,"stale")||flag(health,"stale",true))
            return no("OBSERVATION_UNAVAILABLE","HEALTH_OBSERVATION_STALE",boot);
        JsonObject reconcile=object(health,"bootReconcile");
        if(reconcile==null)return no(flag(health,"bootReconcileReadError",true)?"OBSERVATION_UNAVAILABLE":"WAITING_BOOT",
                flag(health,"bootReconcileReadError",true)?"BOOT_OBSERVATION_INVALID":"BOOT_NOT_STARTED",boot);
        if(!boot.equals(text(reconcile,"bootId")))return no("OBSERVATION_UNAVAILABLE","BOOT_CHANGED_OR_STALE",boot);
        String status=text(reconcile,"status");
        if(status==null||!java.util.Set.of("READY","RECONCILING","HELD","FAILED","DEGRADED").contains(status))
            return no("OBSERVATION_UNAVAILABLE","BOOT_OBSERVATION_INVALID",boot);
        if("HELD".equals(status)||flag(maintenance,"bootHeld",true))return no("BLOCKED","BOOT_MAINTENANCE_HELD",boot);
        if("FAILED".equals(status)||"DEGRADED".equals(status))return no("BLOCKED","BOOT_RECONCILE_FAILED",boot);
        if(!"READY".equals(status))return no("WAITING_BOOT","BOOT_RECONCILING",boot);
        // READY/SKIPPED means no configured services. detailedVerified is not an initial-config prerequisite.
        if(!flag(reconcile,"runtimeReady",true)||text(reconcile,"phase")==null||!java.util.Set.of("COMPLETE","SKIPPED").contains(text(reconcile,"phase")))return no("OBSERVATION_UNAVAILABLE","BOOT_READY_PROOF_INVALID",boot);
        if(!flag(maintenance,"success",true)||!flag(maintenance,"bootHeld",false))
            return no("OBSERVATION_UNAVAILABLE","MAINTENANCE_UNOBSERVED",boot);
        if(!flag(idle,"success",true)||!flag(idle,"writerLockSupported",true)
                ||!booleanField(idle,"writerIdle")||!booleanField(idle,"bootChildActive")||!booleanField(idle,"bootRecoveryRequired")
                ||text(idle,"status")==null||!java.util.Set.of("WRITER_IDLE","WRITER_BUSY").contains(text(idle,"status"))
                ||flag(idle,"writerIdle",true)!=("WRITER_IDLE".equals(text(idle,"status"))))
            return no("OBSERVATION_UNAVAILABLE","WRITER_UNOBSERVED",boot);
        if(flag(idle,"bootChildActive",true)||flag(idle,"bootRecoveryRequired",true))
            return no("WAITING_BOOT","BOOT_CHILD_ACTIVE",boot);
        if(!databaseIdle||!flag(idle,"writerIdle",true)||!"WRITER_IDLE".equals(text(idle,"status")))
            return no("WAITING_WRITER","WRITER_ACTIVE",boot);
        if(!formatterIdle)return no("BLOCKED","FORMATTER_ACTIVE_OR_UNOBSERVED",boot);
        if(text(after,"pendingOperationUuid")!=null)return no("BLOCKED","NATIVE_GENERATION_RECOVERY_REQUIRED",boot);
        return new Result(true,"READY","CURRENT_BOOT_READY",boot);
    }
}

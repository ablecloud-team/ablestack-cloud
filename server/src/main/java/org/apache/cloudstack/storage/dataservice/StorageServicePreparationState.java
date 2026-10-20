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

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Current-BOOT startup readiness. Cached and live health are distinct supported wire formats. */
public final class StorageServicePreparationState {
    private StorageServicePreparationState() { }

    public static final class Result {
        public final boolean ready;
        public final String status, reason, bootId;
        Result(boolean ready, String status, String reason, String bootId) {
            this.ready=ready;this.status=status;this.reason=reason;this.bootId=bootId;
        }
    }

    private static Result no(String status,String reason,String boot) {return new Result(false,status,reason,boot);}
    private static String text(JsonObject v,String k) {return v!=null&&v.has(k)&&v.get(k).isJsonPrimitive()&&v.get(k).getAsJsonPrimitive().isString()?v.get(k).getAsString():null;}
    private static boolean flag(JsonObject v,String k,boolean b) {return booleanField(v,k)&&v.get(k).getAsBoolean()==b;}
    private static boolean booleanField(JsonObject v,String k) {return v!=null&&v.has(k)&&v.get(k).isJsonPrimitive()&&v.get(k).getAsJsonPrimitive().isBoolean();}
    private static JsonObject object(JsonObject v,String k) {return v!=null&&v.has(k)&&v.get(k).isJsonObject()?v.getAsJsonObject(k):null;}
    private static boolean absent(JsonObject v,String k) {return v==null||!v.has(k)||v.get(k).isJsonNull();}
    private static boolean uuid(String value) {return value!=null&&value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
    private static Double epoch(JsonObject v) {
        try {
            JsonElement item=v==null?null:v.get("generatedEpoch");
            if(item==null||!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isNumber())return null;
            double value=item.getAsDouble();return Double.isFinite(value)&&value>=0&&value<1e12?value:null;
        } catch(RuntimeException invalid) {return null;}
    }
    private static boolean fresh(JsonObject health,long now) {
        Double value=epoch(health);return value!=null&&now/1000.0-value>=-5&&now/1000.0-value<=30;
    }
    private static boolean nullableString(JsonObject v,String k) {
        return v!=null&&v.has(k)&&(v.get(k).isJsonNull()||v.get(k).isJsonPrimitive()&&v.get(k).getAsJsonPrimitive().isString());
    }
    private static boolean nativeGeneration(JsonObject v) {
        return flag(v,"success",true)&&flag(v,"generationSupported",true)&&nullableString(v,"pendingOperationUuid")&&uuid(text(v,"bootId"));
    }
    private static boolean healthWire(JsonObject health) {
        String status=text(health,"status");
        return booleanField(health,"success")&&status!=null&&java.util.Set.of("ok","degraded","error","reconciling").contains(status)
                &&(!health.has("cached")||booleanField(health,"cached"))
                &&(!health.has("stale")||booleanField(health,"stale"))
                &&(!health.has("bootReconcileReadError")||booleanField(health,"bootReconcileReadError"));
    }
    private static boolean healthyPendingObservation(JsonObject health) {
        return flag(health,"success",true)&&java.util.Set.of("ok","degraded","reconciling").contains(text(health,"status"));
    }

    public static boolean databaseRecoveryRequired(String state,long revision,long committed) {
        return revision>committed&&state!=null&&java.util.Set.of("RECOVERY_REQUIRED","BLOCKED","APPLY_FAILED").contains(state);
    }

    public static Result evaluate(boolean vmRunning,boolean databaseIdle,boolean formatterIdle,
            JsonObject health,JsonObject before,JsonObject after,JsonObject idle,JsonObject maintenance,long now) {
        if(!vmRunning)return no("WAITING_VM","VM_NOT_RUNNING",null);
        String boot=text(before,"bootId"),lastBoot=text(after,"bootId");
        if(!uuid(boot))return no("OBSERVATION_UNAVAILABLE","CURRENT_BOOT_UNOBSERVED",null);
        if(!nativeGeneration(before)||!nativeGeneration(after))return no("OBSERVATION_UNAVAILABLE","GENERATION_OBSERVATION_INVALID",boot);
        if(!flag(maintenance,"success",true)||!booleanField(maintenance,"bootHeld"))
            return no("OBSERVATION_UNAVAILABLE","MAINTENANCE_UNOBSERVED",lastBoot);
        if(flag(maintenance,"bootHeld",true))return no("BLOCKED","BOOT_MAINTENANCE_HELD",lastBoot);
        if(!flag(idle,"success",true)||!flag(idle,"writerLockSupported",true)||!booleanField(idle,"writerIdle")
                ||!booleanField(idle,"bootChildActive")||!booleanField(idle,"bootRecoveryRequired")
                ||text(idle,"status")==null||!java.util.Set.of("WRITER_IDLE","WRITER_BUSY").contains(text(idle,"status"))
                ||flag(idle,"writerIdle",true)!=("WRITER_IDLE".equals(text(idle,"status"))))
            return no("OBSERVATION_UNAVAILABLE","WRITER_UNOBSERVED",lastBoot);
        if(!formatterIdle)return no("BLOCKED","FORMATTER_ACTIVE_OR_UNOBSERVED",lastBoot);
        if(text(after,"pendingOperationUuid")!=null)return no("BLOCKED","NATIVE_GENERATION_RECOVERY_REQUIRED",lastBoot);
        if(!healthWire(health))return no("OBSERVATION_UNAVAILABLE","HEALTH_OBSERVATION_INVALID",lastBoot);
        if(!fresh(health,now)||flag(health,"stale",true))return no("OBSERVATION_UNAVAILABLE","HEALTH_OBSERVATION_STALE",lastBoot);
        if(flag(health,"bootReconcileReadError",true))return no("OBSERVATION_UNAVAILABLE","BOOT_OBSERVATION_INVALID",lastBoot);
        String healthBoot=text(health,"bootId");
        if(!absent(health,"bootId")&&!uuid(healthBoot))return no("OBSERVATION_UNAVAILABLE","HEALTH_BOOT_OBSERVATION_INVALID",lastBoot);
        if(!absent(health,"bootReconcile")&&object(health,"bootReconcile")==null)
            return no("OBSERVATION_UNAVAILABLE","BOOT_OBSERVATION_INVALID",lastBoot);
        JsonObject reconcile=object(health,"bootReconcile");
        if(reconcile!=null) {
            String receiptBoot=text(reconcile,"bootId"),receiptStatus=text(reconcile,"status");
            if(!uuid(receiptBoot)||healthBoot==null||!receiptBoot.equals(healthBoot)||receiptStatus==null
                    ||!java.util.Set.of("READY","RECONCILING","HELD","FAILED","DEGRADED").contains(receiptStatus))
                return no("OBSERVATION_UNAVAILABLE","BOOT_OBSERVATION_INVALID",lastBoot);
        }
        if(!flag(health,"success",true)||"error".equals(text(health,"status"))) {
            // Preserve the more specific protected current-BOOT failure/hold; an explicit collector error is never READY or normal waiting.
            if(boot.equals(lastBoot)&&lastBoot.equals(healthBoot)&&reconcile!=null) {
                String failedBoot=text(reconcile,"status");
                if("HELD".equals(failedBoot))return no("BLOCKED","BOOT_MAINTENANCE_HELD",lastBoot);
                if("FAILED".equals(failedBoot)||"DEGRADED".equals(failedBoot))return no("BLOCKED","BOOT_RECONCILE_FAILED",lastBoot);
            }
            return no("OBSERVATION_UNAVAILABLE","HEALTH_OBSERVATION_FAILED",lastBoot);
        }
        if(!boot.equals(lastBoot))return no("WAITING_BOOT","BOOT_CHANGED_DURING_OBSERVATION",lastBoot);
        if(absent(health,"bootId")) {
            // A successful live health response has no BOOT fields until monitor/boot publishes its cache.
            // This is only a read-only wait, never permission to invent BOOT authority from generation status.
            return no(healthyPendingObservation(health)?"WAITING_BOOT":"OBSERVATION_UNAVAILABLE",
                    healthyPendingObservation(health)?"HEALTH_BOOT_METADATA_PENDING":"HEALTH_OBSERVATION_FAILED",lastBoot);
        }
        if(!lastBoot.equals(healthBoot))return no(flag(health,"cached",true)?"WAITING_BOOT":"OBSERVATION_UNAVAILABLE",
                flag(health,"cached",true)?"HEALTH_CACHE_PREVIOUS_BOOT":"HEALTH_BOOT_OBSERVATION_INCONSISTENT",lastBoot);
        if(reconcile==null)return no(healthyPendingObservation(health)?"WAITING_BOOT":"OBSERVATION_UNAVAILABLE",
                healthyPendingObservation(health)?"BOOT_RECONCILE_STATUS_PENDING":"HEALTH_OBSERVATION_FAILED",lastBoot);
        String receiptBoot=text(reconcile,"bootId");
        if(!uuid(receiptBoot)||!lastBoot.equals(receiptBoot))return no("OBSERVATION_UNAVAILABLE","BOOT_OBSERVATION_INVALID",lastBoot);
        String status=text(reconcile,"status");
        if(status==null||!java.util.Set.of("READY","RECONCILING","HELD","FAILED","DEGRADED").contains(status))
            return no("OBSERVATION_UNAVAILABLE","BOOT_OBSERVATION_INVALID",lastBoot);
        if("HELD".equals(status))return no("BLOCKED","BOOT_MAINTENANCE_HELD",lastBoot);
        if("FAILED".equals(status)||"DEGRADED".equals(status))return no("BLOCKED","BOOT_RECONCILE_FAILED",lastBoot);
        if(flag(idle,"bootChildActive",true)||flag(idle,"bootRecoveryRequired",true))
            return no("WAITING_BOOT","BOOT_CHILD_ACTIVE",lastBoot);
        if(!"READY".equals(status))return no("WAITING_BOOT","BOOT_RECONCILING",lastBoot);
        // READY/SKIPPED needs no configured services, listeners or existing IN_SYNC generation.
        if(!flag(reconcile,"runtimeReady",true)||text(reconcile,"phase")==null
                ||!java.util.Set.of("COMPLETE","SKIPPED").contains(text(reconcile,"phase")))
            return no("OBSERVATION_UNAVAILABLE","BOOT_READY_PROOF_INVALID",lastBoot);
        if(!databaseIdle||!flag(idle,"writerIdle",true))return no("WAITING_WRITER","WRITER_ACTIVE",lastBoot);
        return new Result(true,"READY","CURRENT_BOOT_READY",lastBoot);
    }

    /** Only bounded public comparison fields. Never include raw health, configuration, stderr or credentials. */
    public static JsonObject diagnostics(JsonObject before,JsonObject after,JsonObject health,long now) {
        JsonObject value=new JsonObject();
        diagnosticBoot(value,"nativeBootBefore",before,"bootId");
        diagnosticBoot(value,"nativeBootAfter",after,"bootId");
        diagnosticBoot(value,"healthBootId",health,"bootId");
        value.addProperty("healthOrigin",health==null?"UNOBSERVED":!healthWire(health)?"INVALID":flag(health,"cached",true)?"CACHE":"LIVE");
        if(booleanField(health,"success"))value.addProperty("healthSuccess",health.get("success").getAsBoolean());else value.add("healthSuccess",JsonNull.INSTANCE);
        String healthStatus=text(health,"status");
        if(healthStatus==null)value.add("healthStatus",JsonNull.INSTANCE);
        else value.addProperty("healthStatus",java.util.Set.of("ok","degraded","error","reconciling").contains(healthStatus)?healthStatus:"INVALID");
        JsonObject network=object(health,"networkReadiness");String networkStatus=text(network,"status");
        if(networkStatus==null)value.add("networkReadinessStatus",JsonNull.INSTANCE);
        else value.addProperty("networkReadinessStatus",java.util.Set.of("OK","ERROR","UNKNOWN").contains(networkStatus)?networkStatus:"INVALID");
        Double generated=epoch(health);
        if(generated==null) {value.add("healthGeneratedEpoch",JsonNull.INSTANCE);value.add("healthAgeMillis",JsonNull.INSTANCE);}
        else {value.addProperty("healthGeneratedEpoch",generated);value.addProperty("healthAgeMillis",Math.round(now-generated*1000));}
        JsonObject progress=object(health,"bootReconcile");value.addProperty("bootProgressPresent",progress!=null);
        diagnosticBoot(value,"bootProgressBootId",progress,"bootId");
        String status=text(progress,"status");
        if(status==null)value.add("bootProgressStatus",JsonNull.INSTANCE);
        else value.addProperty("bootProgressStatus",java.util.Set.of("READY","RECONCILING","HELD","FAILED","DEGRADED").contains(status)?status:"INVALID");
        return value;
    }
    private static void diagnosticBoot(JsonObject target,String key,JsonObject source,String field) {
        if(absent(source,field))target.add(key,JsonNull.INSTANCE);
        else {String value=text(source,field);target.addProperty(key,uuid(value)?value:"INVALID");}
    }
}

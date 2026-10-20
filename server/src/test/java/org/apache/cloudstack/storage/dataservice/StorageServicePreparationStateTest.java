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
import com.google.gson.*;
import org.junit.Assert;
import org.junit.Test;
public class StorageServicePreparationStateTest {
    static final long NOW=1792483200000L;static final String BOOT="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    static class Fixture {
        JsonObject health=new JsonObject(),before=new JsonObject(),after,idle=new JsonObject(),maintenance=new JsonObject(),boot=new JsonObject();
        Fixture(){
            before.addProperty("success",true);before.addProperty("generationSupported",true);before.addProperty("bootId",BOOT);
            before.add("generation",new JsonObject());before.add("pendingOperationUuid",JsonNull.INSTANCE);before.addProperty("generationStatus","UNVERIFIED");
            after=before.deepCopy();health.addProperty("bootId",BOOT);health.addProperty("generatedEpoch",NOW/1000.0);
            health.addProperty("success",true);health.addProperty("status","ok");health.addProperty("cached",true);
            boot.addProperty("bootId",BOOT);boot.addProperty("status","READY");boot.addProperty("phase","SKIPPED");
            boot.addProperty("runtimeReady",true);boot.addProperty("detailedVerified",false);health.add("bootReconcile",boot);
            idle.addProperty("success",true);idle.addProperty("status","WRITER_IDLE");idle.addProperty("writerIdle",true);
            idle.addProperty("writerLockSupported",true);idle.addProperty("bootChildActive",false);idle.addProperty("bootRecoveryRequired",false);
            maintenance.addProperty("success",true);maintenance.addProperty("bootHeld",false);
        }
        StorageServicePreparationState.Result evaluate(){return StorageServicePreparationState.evaluate(true,true,true,health,before,after,idle,maintenance,NOW);}
    }
    @Test public void freshEmptyConfigurationReadySkippedDoesNotRequireExistingListenersOrInSyncGeneration(){
        Fixture f=new Fixture();Assert.assertTrue(f.evaluate().ready);Assert.assertEquals("CURRENT_BOOT_READY",f.evaluate().reason);
        f.boot.addProperty("desiredHash","historical-boot-empty-config");
        f.after.addProperty("generationStatus","IN_SYNC");f.after.getAsJsonObject("generation").addProperty("revision",7);
        Assert.assertTrue("BOOT completion is not a comparison with subsequently changed configuration",f.evaluate().ready);
    }
    @Test public void runningVmDoesNotImplyReadyAndMissingBootReceiptStaysPending(){
        Fixture f=new Fixture();f.health.remove("bootReconcile");Assert.assertEquals("BOOT_RECONCILE_STATUS_PENDING",f.evaluate().reason);
        f.health.addProperty("bootReconcileReadError",true);Assert.assertEquals("OBSERVATION_UNAVAILABLE",f.evaluate().status);
        Assert.assertEquals("WAITING_VM",StorageServicePreparationState.evaluate(false,true,true,f.health,f.before,f.after,f.idle,f.maintenance,NOW).status);
    }
    @Test public void reconcilingOrLiveBootChildWaitsWithoutAuthorizingWriter(){
        Fixture f=new Fixture();f.boot.addProperty("status","RECONCILING");Assert.assertEquals("WAITING_BOOT",f.evaluate().status);
        f=new Fixture();f.idle.addProperty("bootChildActive",true);f.idle.addProperty("writerIdle",false);f.idle.addProperty("status","WRITER_BUSY");
        Assert.assertEquals("BOOT_CHILD_ACTIVE",f.evaluate().reason);
        f=new Fixture();f.idle.addProperty("bootRecoveryRequired",true);Assert.assertEquals("BOOT_CHILD_ACTIVE",f.evaluate().reason);
    }
    @Test public void failureAndMaintenanceHoldAreBlockedInsteadOfBeingRetriedAsStartup(){
        for(String state:new String[]{"FAILED","DEGRADED","HELD"}){
            Fixture f=new Fixture();f.boot.addProperty("status",state);Assert.assertEquals(state,"BLOCKED",f.evaluate().status);
        }
        Fixture f=new Fixture();f.maintenance.addProperty("bootHeld",true);Assert.assertEquals("BOOT_MAINTENANCE_HELD",f.evaluate().reason);
    }
    @Test public void staleFutureAndChangedBootObservationsCannotAuthorizeMutation(){
        for(double age:new double[]{31,-6,Double.NaN,Double.POSITIVE_INFINITY}){
            Fixture f=new Fixture();f.health.addProperty("generatedEpoch",NOW/1000.0-age);Assert.assertFalse(f.evaluate().ready);
        }
        Fixture changed=new Fixture();changed.after.addProperty("bootId","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        Assert.assertEquals("BOOT_CHANGED_DURING_OBSERVATION",changed.evaluate().reason);
        Assert.assertEquals("WAITING_BOOT",changed.evaluate().status);
        Assert.assertEquals("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",changed.evaluate().bootId);
        for(String field:new String[]{"health","boot"}){
            Fixture f=new Fixture();JsonObject v=field.equals("health")?f.health:f.boot;
            v.addProperty("bootId","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");Assert.assertEquals("BOOT_OBSERVATION_INVALID",f.evaluate().reason);
        }
    }
    @Test public void incompleteOrForgedBootReadyAndStringHealthTimeNeverPass(){
        Fixture f=new Fixture();f.boot.addProperty("phase","WAIT_RUNTIME_READY");Assert.assertEquals("BOOT_READY_PROOF_INVALID",f.evaluate().reason);
        f=new Fixture();f.boot.remove("phase");Assert.assertEquals("BOOT_READY_PROOF_INVALID",f.evaluate().reason);
        f=new Fixture();f.health.addProperty("generatedEpoch",String.valueOf(NOW/1000.0));Assert.assertEquals("HEALTH_OBSERVATION_STALE",f.evaluate().reason);
        f=new Fixture();f.health.addProperty("stale","false");Assert.assertEquals("HEALTH_OBSERVATION_INVALID",f.evaluate().reason);
        f=new Fixture();f.boot.addProperty("runtimeReady","true");Assert.assertEquals("BOOT_READY_PROOF_INVALID",f.evaluate().reason);
    }
    @Test public void missingOrWrongTypedWriterProofIsUnavailable(){
        for(String key:new String[]{"success","writerLockSupported","writerIdle","bootChildActive","bootRecoveryRequired"}){
            for(JsonElement invalid:new JsonElement[]{JsonNull.INSTANCE,new JsonPrimitive("false"),new JsonPrimitive(0),new JsonObject()}){
                Fixture f=new Fixture();f.idle.add(key,invalid);Assert.assertEquals(key,"OBSERVATION_UNAVAILABLE",f.evaluate().status);
            }
        }
        Fixture f=new Fixture();f.idle.addProperty("status","WRITER_BUSY");Assert.assertEquals("WRITER_UNOBSERVED",f.evaluate().reason);
        f=new Fixture();f.idle.remove("status");Assert.assertEquals("WRITER_UNOBSERVED",f.evaluate().reason);
    }
    @Test public void missingOrWrongTypedGenerationProofIsUnavailable(){
        for(String key:new String[]{"success","generationSupported","pendingOperationUuid"}){
            Fixture f=new Fixture();f.before.remove(key);Assert.assertEquals("GENERATION_OBSERVATION_INVALID",f.evaluate().reason);
            f=new Fixture();f.after.addProperty(key,1);Assert.assertEquals("GENERATION_OBSERVATION_INVALID",f.evaluate().reason);
        }
    }
    @Test public void busyDatabaseNativeWriterFormatterAndPendingGenerationPreserveSource(){
        Fixture f=new Fixture();
        Assert.assertEquals("WAITING_WRITER",StorageServicePreparationState.evaluate(true,false,true,f.health,f.before,f.after,f.idle,f.maintenance,NOW).status);
        Assert.assertEquals("FORMATTER_ACTIVE_OR_UNOBSERVED",StorageServicePreparationState.evaluate(true,true,false,f.health,f.before,f.after,f.idle,f.maintenance,NOW).reason);
        f.idle.addProperty("writerIdle",false);f.idle.addProperty("status","WRITER_BUSY");Assert.assertEquals("WAITING_WRITER",f.evaluate().status);
        f=new Fixture();f.after.addProperty("pendingOperationUuid","pending");Assert.assertEquals("NATIVE_GENERATION_RECOVERY_REQUIRED",f.evaluate().reason);
    }
    @Test public void unresolvedTerminalConfigurationNeedsRecoveryInsteadOfWaitingForWriterTimeout(){
        for(String state:new String[]{"RECOVERY_REQUIRED","BLOCKED","APPLY_FAILED"}){
            Assert.assertTrue(state,StorageServicePreparationState.databaseRecoveryRequired(state,2,1));
            Assert.assertFalse("Already committed historical state is not a forward writer",StorageServicePreparationState.databaseRecoveryRequired(state,1,1));
        }
        for(String state:new String[]{"RUNNING","COMPLETE","ROLLED_BACK","COMPLETE_NO_CONFIG_CHANGE"})
            Assert.assertFalse(state,StorageServicePreparationState.databaseRecoveryRequired(state,2,1));
    }
    @Test public void actualLiveShapeWaitsUntilCurrentBootCachePublishesWithoutChangingGeneration(){
        Fixture f=new Fixture();f.health.remove("bootId");f.health.remove("bootReconcile");f.health.remove("cached");
        String original=f.health.toString(),generation=f.before.toString();
        Assert.assertEquals("HEALTH_BOOT_METADATA_PENDING",f.evaluate().reason);Assert.assertFalse(f.evaluate().ready);
        Assert.assertEquals(original,f.health.toString());Assert.assertFalse(f.health.has("bootId"));Assert.assertEquals(generation,f.before.toString());
        f.health.addProperty("bootId",BOOT);Assert.assertEquals("BOOT_RECONCILE_STATUS_PENDING",f.evaluate().reason);
        f.health.add("bootReconcile",f.boot);f.health.addProperty("cached",true);Assert.assertTrue(f.evaluate().ready);
        Assert.assertEquals("UNVERIFIED",f.after.get("generationStatus").getAsString());Assert.assertEquals(0,f.after.getAsJsonObject("generation").size());
    }
    @Test public void internallyConsistentPreviousBootCacheWaitsAndLiveForeignBootRemainsAnError(){
        Fixture f=new Fixture();String previous="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
        f.health.addProperty("bootId",previous);f.boot.addProperty("bootId",previous);
        Assert.assertEquals("HEALTH_CACHE_PREVIOUS_BOOT",f.evaluate().reason);Assert.assertEquals("WAITING_BOOT",f.evaluate().status);
        f.health.remove("cached");Assert.assertEquals("HEALTH_BOOT_OBSERVATION_INCONSISTENT",f.evaluate().reason);
        Assert.assertEquals("OBSERVATION_UNAVAILABLE",f.evaluate().status);
    }
    @Test public void coldObservationDoesNotMaskHeldFormatterPendingGenerationOrInvalidWire(){
        Fixture f=new Fixture();f.health.remove("bootId");f.health.remove("bootReconcile");f.health.remove("cached");
        f.maintenance.addProperty("bootHeld",true);Assert.assertEquals("BOOT_MAINTENANCE_HELD",f.evaluate().reason);
        f.maintenance.addProperty("bootHeld",false);
        Assert.assertEquals("BLOCKED",StorageServicePreparationState.evaluate(true,true,false,f.health,f.before,f.after,f.idle,f.maintenance,NOW).status);
        f.after.addProperty("pendingOperationUuid",BOOT);Assert.assertEquals("NATIVE_GENERATION_RECOVERY_REQUIRED",f.evaluate().reason);
        f.after.add("pendingOperationUuid",JsonNull.INSTANCE);
        f.health.addProperty("bootReconcileReadError",true);Assert.assertEquals("BOOT_OBSERVATION_INVALID",f.evaluate().reason);
        f.health.remove("bootReconcileReadError");f.health.addProperty("success",false);f.health.addProperty("status","error");
        Assert.assertEquals("HEALTH_OBSERVATION_FAILED",f.evaluate().reason);
        f.health.addProperty("success",true);f.health.addProperty("status","ok");f.health.addProperty("cached","true");
        Assert.assertEquals("HEALTH_OBSERVATION_INVALID",f.evaluate().reason);
    }
    @Test public void malformedMetadataAndPartialContradictoryBootReceiptAreNotStartupWaiting(){
        for(JsonElement bad:new JsonElement[]{new JsonPrimitive(7),new JsonPrimitive("not-a-uuid"),new JsonObject()}){
            Fixture f=new Fixture();f.health.add("bootId",bad);Assert.assertEquals("HEALTH_BOOT_OBSERVATION_INVALID",f.evaluate().reason);
            f=new Fixture();f.health.add("bootReconcile",bad);Assert.assertEquals("BOOT_OBSERVATION_INVALID",f.evaluate().reason);
        }
        Fixture f=new Fixture();f.health.remove("bootId");Assert.assertEquals("BOOT_OBSERVATION_INVALID",f.evaluate().reason);
        f=new Fixture();f.health.addProperty("success","true");Assert.assertEquals("HEALTH_OBSERVATION_INVALID",f.evaluate().reason);
        f=new Fixture();f.health.remove("generatedEpoch");Assert.assertEquals("HEALTH_OBSERVATION_STALE",f.evaluate().reason);
    }
    @Test public void diagnosticsContainOnlyBoundedComparisonFieldsAndNeverRawHealthOrConfiguration(){
        Fixture f=new Fixture();f.health.addProperty("password","must-not-escape");f.before.addProperty("secret","must-not-escape");
        f.health.addProperty("bootId","must-not-escape");f.boot.addProperty("status","must-not-escape");
        JsonObject d=StorageServicePreparationState.diagnostics(f.before,f.after,f.health,NOW);
        Assert.assertEquals("CACHE",d.get("healthOrigin").getAsString());Assert.assertEquals("INVALID",d.get("healthBootId").getAsString());
        Assert.assertEquals("INVALID",d.get("bootProgressStatus").getAsString());Assert.assertFalse(d.toString().contains("must-not-escape"));
        Assert.assertFalse(d.has("configurationDesiredState"));Assert.assertEquals(0,d.get("healthAgeMillis").getAsLong());
        f.health.remove("bootId");f.health.remove("cached");f.health.remove("bootReconcile");
        d=StorageServicePreparationState.diagnostics(f.before,f.after,f.health,NOW);
        Assert.assertEquals("LIVE",d.get("healthOrigin").getAsString());Assert.assertTrue(d.get("healthBootId").isJsonNull());
    }
    @Test public void explicitCollectorFailureCannotBecomeReadyOrNormalWaitingEvenWithReadyBootReceipt(){
        Fixture f=new Fixture();f.health.addProperty("success",false);f.health.addProperty("status","error");
        JsonObject network=new JsonObject();network.addProperty("status","ERROR");f.health.add("networkReadiness",network);
        Assert.assertEquals("HEALTH_OBSERVATION_FAILED",f.evaluate().reason);Assert.assertEquals("OBSERVATION_UNAVAILABLE",f.evaluate().status);
        JsonObject d=StorageServicePreparationState.diagnostics(f.before,f.after,f.health,NOW);
        Assert.assertFalse(d.get("healthSuccess").getAsBoolean());Assert.assertEquals("error",d.get("healthStatus").getAsString());
        Assert.assertEquals("ERROR",d.get("networkReadinessStatus").getAsString());
        f.health.remove("bootId");f.health.remove("bootReconcile");Assert.assertEquals("HEALTH_OBSERVATION_FAILED",f.evaluate().reason);
        f=new Fixture();f.health.addProperty("success",false);f.health.addProperty("status","error");f.boot.addProperty("status","FAILED");
        Assert.assertEquals("BOOT_RECONCILE_FAILED",f.evaluate().reason);
    }
}

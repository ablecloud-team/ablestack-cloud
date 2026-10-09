// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.util.List;
import com.google.gson.JsonObject;
import com.cloud.utils.exception.CloudRuntimeException;

/** Durable public receipts bind all four runtime effects to one generation before LKG promotion. */
public final class StorageRenderedGenerationCoordinator {
    public interface Runtime {
        JsonObject render(String action,JsonObject request);
        JsonObject nativeGenerationStatus();
        default void resumeUnchangedMaintenance() { }
        void save(JsonObject receipt);
        void requireAvailable();
        default void requireCommittedAvailable() {requireAvailable();}
        void verifyAllProtocols();
        default void preparePromotionIdentity(JsonObject receipt) { }
        void commitGeneration(JsonObject receipt);
        void rollbackGeneration(JsonObject receipt);
        void releaseMaintenance();
        void promote();
    }
    private final Runtime runtime;
    private final JsonObject scope;
    public StorageRenderedGenerationCoordinator(Runtime runtime,JsonObject scope) {this.runtime=runtime;this.scope=scope.deepCopy();}
    private boolean yes(JsonObject value,String key) {return value.has(key)&&value.get(key).isJsonPrimitive()&&value.get(key).getAsJsonPrimitive().isBoolean()&&value.get(key).getAsBoolean();}
    private String text(JsonObject value,String key) {return value.has(key)&&value.get(key).isJsonPrimitive()?value.get(key).getAsString():null;}
    private void scoped(JsonObject value) {
        if(!yes(value,"success") || !value.has("scope") || !scope.equals(value.get("scope")))throw new CloudRuntimeException("Rendered receipt belongs to another operation scope");
    }
    private void digest(JsonObject value,String field) {String hash=text(value,field);if(hash==null||!hash.matches("[a-f0-9]{64}"))throw new CloudRuntimeException("Rendered receipt digest is unavailable: "+field);}
    public JsonObject stage(JsonObject request) {
        runtime.requireAvailable();JsonObject receipt=new JsonObject();receipt.add("scope",scope.deepCopy());receipt.addProperty("phase","STAGING");if(request.has("expectedCurrentRenderedSha256"))receipt.add("previousRenderedSha256",request.get("expectedCurrentRenderedSha256").deepCopy());if(request.has("previousGeneration"))receipt.add("previousGeneration",request.get("previousGeneration").deepCopy());runtime.save(receipt);
        JsonObject reply=runtime.render("stage",request);scoped(reply);digest(reply,"renderedManifestSha256");digest(reply,"configurationSha256");
        if(!"STAGED".equals(text(reply,"phase")) || !reply.has("validators") || !reply.get("validators").isJsonObject())throw new CloudRuntimeException("Rendered candidate did not reach its validated staged phase");
        for(String domain:List.of("NFS","SMB","ISCSI","NVMEOF"))if(!yes(reply.getAsJsonObject("validators"),domain))throw new CloudRuntimeException("Rendered candidate has an unverified protocol domain");
        JsonObject checkpoint=reply.has("identityCheckpointRef")&&reply.get("identityCheckpointRef").isJsonObject()?reply.getAsJsonObject("identityCheckpointRef"):null;
        if(checkpoint==null || !text(scope,"operationUuid").equals(text(checkpoint,"operationUuid")))throw new CloudRuntimeException("Rendered identity checkpoint belongs to another operation");digest(checkpoint,"sha256");
        receipt.add("staged",reply.deepCopy());if(request.has("expectedCurrentRenderedSha256"))receipt.add("previousRenderedSha256",request.get("expectedCurrentRenderedSha256").deepCopy());if(request.has("previousGeneration"))receipt.add("previousGeneration",request.get("previousGeneration").deepCopy());receipt.addProperty("phase","STAGED");runtime.save(receipt);return receipt;
    }
    public void activate(JsonObject receipt,JsonObject protectedRequest) {
        runtime.requireAvailable();JsonObject staged=receipt.getAsJsonObject("staged");receipt.addProperty("phase","ACTIVATING");runtime.save(receipt);
        JsonObject reply=runtime.render("activate",protectedRequest);JsonObject activation=reply.has("activation")&&reply.get("activation").isJsonObject()?reply.getAsJsonObject("activation"):null;
        JsonObject current=reply.has("current")&&reply.get("current").isJsonObject()?reply.getAsJsonObject("current"):null;
        if(!yes(reply,"success") || !yes(reply,"bootHeld") || activation==null || current==null || !"VERIFIED".equals(text(activation,"phase"))
                || !scope.equals(activation.get("scope")) || !scope.equals(current.get("scope"))
                || !text(staged,"renderedManifestSha256").equals(text(activation,"targetSha256"))
                || !text(staged,"renderedManifestSha256").equals(text(current,"manifestSha256"))
                || !text(staged,"configurationSha256").equals(text(current,"configurationSha256")))throw new CloudRuntimeException("Rendered runtime activation is not verified under the exact staged generation");
        runtime.verifyAllProtocols();runtime.requireAvailable();receipt.add("activation",reply.deepCopy());receipt.addProperty("phase","VERIFIED");runtime.save(receipt);
    }
    public StorageRenderedRecoveryState.Decision recoveryDecision(JsonObject receipt) {
        return StorageRenderedRecoveryState.observe(scope,receipt,runtime.nativeGenerationStatus(),runtime.render("status",new JsonObject()));
    }
    public void commit(JsonObject receipt,JsonObject finalizeRequest) {
        StorageRenderedRecoveryState.Decision decision=recoveryDecision(receipt);String phase=text(receipt,"phase");
        if(decision!=StorageRenderedRecoveryState.Decision.COMMITTED&&!java.util.Set.of("VERIFIED","GENERATION_COMMITTING").contains(phase))throw new CloudRuntimeException("Rendered commit requires all four verified runtime domains");
        if(decision==StorageRenderedRecoveryState.Decision.COMMITTED){runtime.requireCommittedAvailable();runtime.preparePromotionIdentity(receipt);}else runtime.requireAvailable();runtime.verifyAllProtocols();
        if(!java.util.Set.of("FINALIZED","RELEASED").contains(phase)) {
            if(decision!=StorageRenderedRecoveryState.Decision.COMMITTED){receipt.addProperty("phase","GENERATION_COMMITTING");runtime.save(receipt);}
            runtime.commitGeneration(receipt);receipt.addProperty("phase","GENERATION_COMMITTED");runtime.save(receipt);
        }
        if(decision!=StorageRenderedRecoveryState.Decision.COMMITTED)runtime.preparePromotionIdentity(receipt);
        JsonObject finalized=java.util.Set.of("FINALIZED","RELEASED").contains(phase)?receipt.getAsJsonObject("finalized"):runtime.render("finalize",finalizeRequest);
        JsonObject activation=finalized.has("activation")&&finalized.get("activation").isJsonObject()?finalized.getAsJsonObject("activation"):null;
        JsonObject current=finalized.has("current")&&finalized.get("current").isJsonObject()?finalized.getAsJsonObject("current"):null;
        if(!yes(finalized,"success") || activation==null || current==null || !scope.equals(activation.get("scope")) || !scope.equals(current.get("scope"))
                || !"COMPLETE".equals(text(activation,"phase")) || !text(receipt.getAsJsonObject("staged"),"renderedManifestSha256").equals(text(current,"manifestSha256"))
                || !finalized.has("bootHeld") || !finalized.get("bootHeld").isJsonPrimitive() || !finalized.get("bootHeld").getAsJsonPrimitive().isBoolean() || finalized.get("bootHeld").getAsBoolean())throw new CloudRuntimeException("Rendered finalization has not released the verified generation boot hold");
        receipt.add("finalized",finalized.deepCopy());receipt.addProperty("phase","FINALIZED");runtime.save(receipt);
        runtime.requireCommittedAvailable();if(!"RELEASED".equals(phase)){runtime.releaseMaintenance();receipt.addProperty("phase","RELEASED");runtime.save(receipt);}runtime.promote();receipt.addProperty("phase","COMPLETE");runtime.save(receipt);
    }
    public void rollback(JsonObject receipt,JsonObject protectedRequest) {
        if(recoveryDecision(receipt)==StorageRenderedRecoveryState.Decision.COMMITTED)throw new CloudRuntimeException("Native target generation committed; forward recovery is required");
        JsonObject observed=runtime.render("status",new JsonObject()),journal=observed.has("activation")&&observed.get("activation").isJsonObject()?observed.getAsJsonObject("activation"):null;
        boolean activated=journal!=null&&scope.equals(journal.get("scope"));
        receipt.addProperty("phase","ROLLING_BACK");runtime.save(receipt);
        try {
            if(!activated) {
                runtime.rollbackGeneration(receipt);runtime.resumeUnchangedMaintenance();runtime.verifyAllProtocols();receipt.addProperty("recoveryKind","SOURCE_UNCHANGED");receipt.addProperty("phase","ROLLED_BACK");runtime.save(receipt);return;
            }
            JsonObject reply="ROLLED_BACK".equals(text(journal,"phase"))?observed:runtime.render("rollback",protectedRequest);
            JsonObject activation=reply.has("activation")&&reply.get("activation").isJsonObject()?reply.getAsJsonObject("activation"):null;
            JsonObject current=reply.has("current")&&reply.get("current").isJsonObject()?reply.getAsJsonObject("current"):null;
            if(!yes(reply,"success") || activation==null || current==null || !scope.equals(activation.get("scope")) || !"ROLLED_BACK".equals(text(activation,"phase"))
                    || !text(receipt,"previousRenderedSha256").equals(text(current,"manifestSha256")))throw new CloudRuntimeException("Rendered previous generation recovery is not complete");
            runtime.verifyAllProtocols();runtime.rollbackGeneration(receipt);receipt.add("rollback",reply.deepCopy());receipt.addProperty("phase","ROLLED_BACK");runtime.save(receipt);
        } catch(RuntimeException failure) {receipt.addProperty("phase","RECOVERY_REQUIRED");runtime.save(receipt);throw failure;}
    }
}

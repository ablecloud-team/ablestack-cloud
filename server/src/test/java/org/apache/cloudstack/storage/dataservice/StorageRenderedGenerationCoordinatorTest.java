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

import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageRenderedGenerationCoordinatorTest {
    private static final String TARGET="a".repeat(64),PREVIOUS="b".repeat(64),CONFIG="c".repeat(64);
    private static JsonObject scope(){JsonObject s=new JsonObject();s.addProperty("instanceUuid","instance");s.addProperty("operationUuid","operation");s.addProperty("revision",2);return s;}
    private static class Runtime implements StorageRenderedGenerationCoordinator.Runtime {
        final List<String> events=new ArrayList<>();boolean badStageScope,badValidator,finalizeFailure,releaseFailure,committed,activated,finalized,saveCommitFailure,cancelled;JsonObject saved;
        public JsonObject render(String action,JsonObject request) {
            events.add(action);if(action.equals("status"))return status();if(action.equals("stage")){JsonObject r=new JsonObject();r.addProperty("success",true);r.add("scope",scope());if(badStageScope)r.getAsJsonObject("scope").addProperty("operationUuid","foreign");r.addProperty("phase","STAGED");r.addProperty("renderedManifestSha256",TARGET);r.addProperty("configurationSha256",CONFIG);JsonObject validators=new JsonObject();for(String p:List.of("NFS","SMB","ISCSI","NVMEOF"))validators.addProperty(p,!badValidator);r.add("validators",validators);JsonObject checkpoint=new JsonObject();checkpoint.addProperty("operationUuid","operation");checkpoint.addProperty("sha256",PREVIOUS);r.add("identityCheckpointRef",checkpoint);return r;}
            if(action.equals("finalize")&&finalizeFailure)throw new CloudRuntimeException("native finalize held");
            if(action.equals("activate"))activated=true;if(action.equals("finalize"))finalized=true;if(action.equals("rollback"))activated=false;
            JsonObject r=new JsonObject();r.addProperty("success",true);r.addProperty("bootHeld",action.equals("activate"));JsonObject activation=new JsonObject();activation.add("scope",scope());activation.addProperty("phase",action.equals("activate")?"VERIFIED":action.equals("rollback")?"ROLLED_BACK":"COMPLETE");activation.addProperty("targetSha256",TARGET);activation.addProperty("previousSha256",PREVIOUS);r.add("activation",activation);JsonObject current=new JsonObject();current.add("scope",action.equals("rollback")?previousScope():scope());current.addProperty("manifestSha256",action.equals("rollback")?PREVIOUS:TARGET);current.addProperty("configurationSha256",CONFIG);r.add("current",current);return r;
        }
        public void save(JsonObject receipt){if(saveCommitFailure&&"GENERATION_COMMITTED".equals(receipt.get("phase").getAsString())){saveCommitFailure=false;throw new CloudRuntimeException("DB save lost after native commit");}saved=receipt.deepCopy();events.add("save:"+receipt.get("phase").getAsString());Assert.assertFalse(receipt.toString().contains("synthetic-private-key"));}
        public void requireAvailable(){events.add("guard");if(cancelled)throw new StorageOperationCancelledException("cancel requested");}
        public void requireCommittedAvailable(){events.add("committed-guard");}
        public void verifyAllProtocols(){events.add("verify-all4");}
        public JsonObject nativeGenerationStatus(){JsonObject r=new JsonObject();r.addProperty("success",true);r.addProperty("generationSupported",true);JsonObject generation=committed?scope():previous();if(committed)generation.addProperty("configurationSha256",CONFIG);r.add("generation",generation);r.add("pendingOperationUuid",committed?com.google.gson.JsonNull.INSTANCE:new com.google.gson.JsonPrimitive("operation"));r.addProperty("generationStatus",committed?"IN_SYNC":"PENDING");r.addProperty("configurationSha256",activated||committed?CONFIG:"d".repeat(64));return r;}
        public JsonObject status(){JsonObject r=new JsonObject();r.addProperty("success",true);r.addProperty("bootHeld",activated&&!finalized);JsonObject current=new JsonObject();current.add("scope",activated?scope():previousScope());current.addProperty("manifestSha256",activated?TARGET:PREVIOUS);current.addProperty("configurationSha256",activated?CONFIG:"d".repeat(64));r.add("current",current);JsonObject activation=new JsonObject();activation.add("scope",activated?scope():previousScope());activation.addProperty("phase",activated?(finalized?"COMPLETE":"VERIFIED"):"COMPLETE");activation.addProperty("targetSha256",activated?TARGET:PREVIOUS);activation.addProperty("previousSha256",PREVIOUS);r.add("activation",activation);return r;}
        public void commitGeneration(JsonObject receipt){if(!committed)events.add("generation:verify-commit-finish");committed=true;}
        public void rollbackGeneration(JsonObject receipt){events.add("generation:rollback");committed=false;}
        public void resumeUnchangedMaintenance(){events.add("source-maintenance-resume");}
        public void releaseMaintenance(){events.add("release");if(releaseFailure)throw new CloudRuntimeException("release held");}
        public void promote(){events.add("DB:LKG-COMPLETE");}
    }
    private static JsonObject previousScope(){JsonObject s=scope();s.addProperty("operationUuid","source");s.addProperty("revision",1);return s;}
    private static JsonObject previous(){JsonObject s=previousScope();s.addProperty("configurationSha256","d".repeat(64));return s;}
    private JsonObject request(){JsonObject r=scope();r.addProperty("expectedCurrentRenderedSha256",PREVIOUS);r.add("previousGeneration",previous());return r;}
    @Test public void allFourVerifiedRuntimeAndGenerationFinishPrecedeFinalizeReleaseAndDatabasePromotion() {
        Runtime r=new Runtime();StorageRenderedGenerationCoordinator c=new StorageRenderedGenerationCoordinator(r,scope());JsonObject receipt=c.stage(request()),privateRequest=scope();privateRequest.addProperty("checkpointPrivateKey","synthetic-private-key");c.activate(receipt,privateRequest);c.commit(receipt,privateRequest);
        Assert.assertTrue(r.events.indexOf("activate")<r.events.indexOf("generation:verify-commit-finish"));Assert.assertTrue(r.events.indexOf("generation:verify-commit-finish")<r.events.indexOf("finalize"));Assert.assertTrue(r.events.indexOf("finalize")<r.events.indexOf("release"));Assert.assertTrue(r.events.indexOf("release")<r.events.indexOf("DB:LKG-COMPLETE"));Assert.assertEquals("COMPLETE",receipt.get("phase").getAsString());
    }
    @Test public void foreignScopeOrMissingProtocolValidatorCannotReachActivation() {
        for(boolean scopeFailure:List.of(true,false)){Runtime r=new Runtime();r.badStageScope=scopeFailure;r.badValidator=!scopeFailure;StorageRenderedGenerationCoordinator c=new StorageRenderedGenerationCoordinator(r,scope());Assert.assertThrows(CloudRuntimeException.class,()->c.stage(request()));Assert.assertFalse(r.events.contains("activate"));Assert.assertFalse(r.events.contains("DB:LKG-COMPLETE"));}
    }
    @Test public void finalizeFailureAfterNativeCommitKeepsDatabaseUnpromotedForForwardRecovery() {
        Runtime r=new Runtime();StorageRenderedGenerationCoordinator c=new StorageRenderedGenerationCoordinator(r,scope());JsonObject receipt=c.stage(request());c.activate(receipt,scope());r.finalizeFailure=true;Assert.assertThrows(CloudRuntimeException.class,()->c.commit(receipt,scope()));Assert.assertEquals("GENERATION_COMMITTED",receipt.get("phase").getAsString());Assert.assertFalse(r.events.contains("DB:LKG-COMPLETE"));r.finalizeFailure=false;c.commit(receipt,scope());Assert.assertEquals(1,r.events.stream().filter("generation:verify-commit-finish"::equals).count());
    }
    @Test public void verifiedReleaseFailureCannotClaimCompleteAndRetriesOnlyReleaseAfterFinalization() {
        Runtime r=new Runtime();StorageRenderedGenerationCoordinator c=new StorageRenderedGenerationCoordinator(r,scope());JsonObject receipt=c.stage(request());c.activate(receipt,scope());r.releaseFailure=true;Assert.assertThrows(CloudRuntimeException.class,()->c.commit(receipt,scope()));Assert.assertEquals("FINALIZED",receipt.get("phase").getAsString());Assert.assertFalse(r.events.contains("DB:LKG-COMPLETE"));r.releaseFailure=false;c.commit(receipt,scope());Assert.assertEquals(1,r.events.stream().filter("finalize"::equals).count());
    }
    @Test public void rollbackVerifiesAllOldDomainsBeforeNativeGenerationRollbackAndNeverPromotesTarget() {
        Runtime r=new Runtime();StorageRenderedGenerationCoordinator c=new StorageRenderedGenerationCoordinator(r,scope());JsonObject receipt=c.stage(request());c.activate(receipt,scope());c.rollback(receipt,scope());Assert.assertEquals("ROLLED_BACK",receipt.get("phase").getAsString());Assert.assertTrue(r.events.indexOf("rollback")<r.events.indexOf("generation:rollback"));Assert.assertFalse(r.events.contains("DB:LKG-COMPLETE"));
    }
    @Test public void interruptedAfterNativeCommitBeforeDatabasePhaseSaveMustOnlyRecoverForward() {
        Runtime r=new Runtime();StorageRenderedGenerationCoordinator c=new StorageRenderedGenerationCoordinator(r,scope());JsonObject receipt=c.stage(request());c.activate(receipt,scope());r.saveCommitFailure=true;
        Assert.assertThrows(CloudRuntimeException.class,()->c.commit(receipt,scope()));JsonObject durable=r.saved.deepCopy();Assert.assertEquals("GENERATION_COMMITTING",durable.get("phase").getAsString());
        Assert.assertEquals(StorageRenderedRecoveryState.Decision.COMMITTED,c.recoveryDecision(durable));Assert.assertThrows(CloudRuntimeException.class,()->c.rollback(durable,scope()));
        c.commit(durable,scope());Assert.assertEquals("COMPLETE",durable.get("phase").getAsString());Assert.assertFalse(r.events.contains("rollback"));Assert.assertEquals(1,r.events.stream().filter("generation:verify-commit-finish"::equals).count());
    }
    @Test public void stagedOnlyInterruptionAbortsNativePendingAndRestoresHeldFileServicesWithoutActivationInverse() {
        Runtime r=new Runtime();StorageRenderedGenerationCoordinator c=new StorageRenderedGenerationCoordinator(r,scope());JsonObject receipt=c.stage(request());c.rollback(receipt,scope());
        Assert.assertFalse(r.events.contains("rollback"));Assert.assertFalse(r.events.contains("activate"));Assert.assertEquals("SOURCE_UNCHANGED",receipt.get("recoveryKind").getAsString());Assert.assertEquals("ROLLED_BACK",receipt.get("phase").getAsString());
        Assert.assertTrue(r.events.indexOf("generation:rollback")<r.events.indexOf("source-maintenance-resume"));Assert.assertTrue(r.events.indexOf("source-maintenance-resume")<r.events.lastIndexOf("verify-all4"));
    }
    @Test public void durableStagingReceiptRetainsPreviousPinsEvenWhenStageReplyFailsValidation() {
        Runtime r=new Runtime();r.badValidator=true;StorageRenderedGenerationCoordinator c=new StorageRenderedGenerationCoordinator(r,scope());Assert.assertThrows(CloudRuntimeException.class,()->c.stage(request()));
        JsonObject receipt=r.saved.deepCopy();Assert.assertEquals("STAGING",receipt.get("phase").getAsString());Assert.assertEquals(previous(),receipt.get("previousGeneration"));c.rollback(receipt,scope());Assert.assertEquals("ROLLED_BACK",receipt.get("phase").getAsString());
    }
    @Test public void cancellationAfterDurableCommitStillFinalizesAndReleasesBeforeTargetPromotion() {
        Runtime r=new Runtime();StorageRenderedGenerationCoordinator c=new StorageRenderedGenerationCoordinator(r,scope());JsonObject receipt=c.stage(request());c.activate(receipt,scope());r.finalizeFailure=true;Assert.assertThrows(CloudRuntimeException.class,()->c.commit(receipt,scope()));r.finalizeFailure=false;r.cancelled=true;c.commit(receipt,scope());Assert.assertEquals("COMPLETE",receipt.get("phase").getAsString());Assert.assertTrue(r.events.contains("release"));Assert.assertFalse(r.events.contains("rollback"));
    }
}

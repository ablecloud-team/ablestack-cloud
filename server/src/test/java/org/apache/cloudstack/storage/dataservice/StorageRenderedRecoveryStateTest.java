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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageRenderedRecoveryStateTest {
    private final JsonObject scope=JsonParser.parseString("{\"instanceUuid\":\"instance\",\"operationUuid\":\"target\",\"revision\":2}").getAsJsonObject();
    private final JsonObject previous=JsonParser.parseString("{\"instanceUuid\":\"instance\",\"operationUuid\":\"source\",\"revision\":1,\"configurationSha256\":\"old-config\",\"verifiedAt\":123}").getAsJsonObject();
    private JsonObject receipt(){JsonObject r=new JsonObject();r.add("previousGeneration",previous.deepCopy());r.addProperty("previousRenderedSha256","old-rendered");r.addProperty("phase","ACTIVATING");JsonObject staged=new JsonObject();staged.addProperty("renderedManifestSha256","new-rendered");staged.addProperty("configurationSha256","new-config");r.add("staged",staged);return r;}
    private JsonObject nativeState(boolean committed){JsonObject r=new JsonObject();r.addProperty("success",true);r.addProperty("generationSupported",true);JsonObject g=committed?scope.deepCopy():previous.deepCopy();if(committed)g.addProperty("configurationSha256","new-config");r.add("generation",g);r.addProperty("pendingOperationUuid","target");r.addProperty("generationStatus","PENDING");r.addProperty("configurationSha256","new-config");return r;}
    private JsonObject rendered(){JsonObject r=new JsonObject();r.addProperty("success",true);r.addProperty("bootHeld",true);JsonObject current=new JsonObject();current.add("scope",scope.deepCopy());current.addProperty("manifestSha256","new-rendered");current.addProperty("configurationSha256","new-config");r.add("current",current);JsonObject activation=new JsonObject();activation.add("scope",scope.deepCopy());activation.addProperty("phase","VERIFIED");activation.addProperty("targetSha256","new-rendered");activation.addProperty("previousSha256","old-rendered");r.add("activation",activation);return r;}
    @Test public void currentCommitWinsOverLaggingActivationReceiptWhileNativeFinishRemainsPending(){Assert.assertEquals(StorageRenderedRecoveryState.Decision.COMMITTED,StorageRenderedRecoveryState.observe(scope,receipt(),nativeState(true),rendered()));}
    @Test public void sourceGenerationWithTargetRuntimeHasNotCrossedNativeCommitAndCanUseExactInverse(){Assert.assertEquals(StorageRenderedRecoveryState.Decision.UNCOMMITTED,StorageRenderedRecoveryState.observe(scope,receipt(),nativeState(false),rendered()));}
    @Test public void foreignPendingScopeOrThirdPointerRejectsBothForwardAndInverse(){JsonObject n=nativeState(true);n.addProperty("pendingOperationUuid","foreign");Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedRecoveryState.observe(scope,receipt(),n,rendered()));JsonObject r=rendered();r.getAsJsonObject("current").addProperty("manifestSha256","third");Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedRecoveryState.observe(scope,receipt(),nativeState(false),r));}
    @Test public void nativeDigestDriftAndStringBooleanRejectCommittedRecoveryBeforeAnyReplay(){JsonObject n=nativeState(true);n.addProperty("configurationSha256","drift");Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedRecoveryState.observe(scope,receipt(),n,rendered()));JsonObject r=rendered();r.addProperty("bootHeld","true");Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedRecoveryState.observe(scope,receipt(),nativeState(true),r));}
    @Test public void sameRevisionForeignNativeGenerationAndForeignActivationCannotBeAdopted(){JsonObject n=nativeState(false);n.getAsJsonObject("generation").addProperty("operationUuid","foreign");Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedRecoveryState.observe(scope,receipt(),n,rendered()));JsonObject r=rendered();r.getAsJsonObject("activation").getAsJsonObject("scope").addProperty("operationUuid","foreign");Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedRecoveryState.observe(scope,receipt(),nativeState(true),r));}
}

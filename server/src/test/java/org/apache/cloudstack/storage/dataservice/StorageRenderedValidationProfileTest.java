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
import com.google.gson.JsonArray;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageRenderedValidationProfileTest {
    private JsonObject bindings(){JsonObject value=new JsonObject(),volumes=new JsonObject();for(String type:new String[]{"ROOT","DATADISK"}){JsonObject disk=new JsonObject();disk.addProperty("type",type);disk.addProperty("provisioningType","SPARSE");disk.addProperty("attachedToFixture",true);disk.addProperty("ownerAndZoneVerified",true);disk.addProperty("newDataWithoutBacking",type.equals("DATADISK"));volumes.add(type,disk);}value.add("volumes",volumes);return value;}
    private JsonObject artifact(){JsonObject a=new JsonObject();a.addProperty("schemaVersion",1);a.addProperty("kind","NEW_SPARSE_ALL4_VALIDATION");a.addProperty("instanceUuid","new-disposable");a.addProperty("instanceName","exact-name");a.addProperty("newDisposableFixture",true);a.addProperty("originalDataExcluded",true);a.addProperty("expiresAtMillis",System.currentTimeMillis()+3600000);a.addProperty("expectedCliSha256","a".repeat(64));a.addProperty("sourceCommit","b".repeat(40));a.add("bindings",bindings());JsonArray excluded=new JsonArray();for(int id:new int[]{39,41,43,48,49,50,51})excluded.add("original-"+id);a.add("excludedInstanceUuids",excluded);return a;}
    @Test public void protectedExplicitDisposableFixtureDoesNotEnableProductionCapability() {JsonObject a=artifact();StorageRenderedValidationProfile.verify(a,"new-disposable","exact-name",bindings(),System.currentTimeMillis());Assert.assertFalse(a.has("fullFourProtocolActivationSupported"));}
    @Test public void originalOrForeignInstanceAndChangedRootDataBindingsCannotEnterTheProfile() {JsonObject a=artifact();Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedValidationProfile.verify(a,"original-50","exact-name",bindings(),System.currentTimeMillis()));JsonObject changed=bindings();changed.addProperty("rootVolumeUuid","foreign");Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedValidationProfile.verify(a,"new-disposable","exact-name",changed,System.currentTimeMillis()));}
    @Test public void thinReusedClonedOrUnknownDataRemainIneligibleBeforeAnyGuestWriter() {for(String field:new String[]{"provisioningType","newDataWithoutBacking","ownerAndZoneVerified"}){JsonObject b=bindings();JsonObject disk=b.getAsJsonObject("volumes").getAsJsonObject("DATADISK");if(field.equals("provisioningType"))disk.addProperty(field,"THIN");else disk.addProperty(field,false);JsonObject a=artifact();a.add("bindings",b);Assert.assertThrows(field,CloudRuntimeException.class,()->StorageRenderedValidationProfile.verify(a,"new-disposable","exact-name",b,System.currentTimeMillis()));}}
    @Test public void expiredOrUnprotectedCallerStyleClaimsCannotReplaceFixtureProof() {JsonObject a=artifact();a.addProperty("expiresAtMillis",1);Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedValidationProfile.verify(a,"new-disposable","exact-name",bindings(),System.currentTimeMillis()));JsonObject string=artifact();string.addProperty("newDisposableFixture","true");Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedValidationProfile.verify(string,"new-disposable","exact-name",bindings(),System.currentTimeMillis()));}
    @Test public void profileReauthorizationCannotEraseImportedBaselineAndThenFallThroughToLegacy() {
        JsonObject previous=new JsonObject();previous.addProperty("baselineImported",true);JsonObject baseline=new JsonObject();baseline.addProperty("manifestSha256","f".repeat(64));previous.add("baselineManifest",baseline);
        JsonObject renewed=StorageRenderedValidationProfile.next(previous,true,2);Assert.assertTrue(renewed.get("baselineImported").getAsBoolean());Assert.assertEquals(baseline,renewed.get("baselineManifest"));Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedValidationProfile.next(renewed,false,3));Assert.assertEquals(2,renewed.get("revision").getAsLong());
    }
    @Test public void approvedDisposableProvenanceSurvivesAgeOnlyAndRejectsPathOwnerOrAttachmentChanges() {
        JsonObject approved=new JsonObject();approved.addProperty("type","DATADISK");approved.addProperty("newDataWithoutBacking",true);approved.addProperty("path","uuid");approved.addProperty("attachedToFixture",true);approved.addProperty("ownerAndZoneVerified",true);JsonObject fresh=approved.deepCopy();fresh.addProperty("newDataWithoutBacking",false);Assert.assertTrue(StorageRenderedValidationProfile.retainedNewData(fresh,approved));
        for(String field:new String[]{"path","attachedToFixture","ownerAndZoneVerified"}){JsonObject changed=fresh.deepCopy();changed.addProperty(field,"different");Assert.assertFalse(StorageRenderedValidationProfile.retainedNewData(changed,approved));}Assert.assertFalse(StorageRenderedValidationProfile.retainedNewData(fresh,null));
    }
    @Test public void fixedHandlerAttestationDoesNotRequireOrSynthesizeProductionFullFourCapability() {
        JsonObject observed=new JsonObject();observed.addProperty("success",true);observed.addProperty("schemaVersion",1);observed.addProperty("renderedGenerationSupported",true);observed.addProperty("fullFourProtocolActivationSupported",false);com.google.gson.JsonArray features=new com.google.gson.JsonArray();features.add("RENDERED_CONFIG_GENERATION_HANDLER");observed.add("supportedFeatures",features);StorageRenderedValidationProfile.requireHandler(observed);Assert.assertFalse(observed.get("fullFourProtocolActivationSupported").getAsBoolean());observed.addProperty("renderedGenerationSupported","true");Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedValidationProfile.requireHandler(observed));observed.addProperty("renderedGenerationSupported",true);observed.remove("supportedFeatures");Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedValidationProfile.requireHandler(observed));
    }
}

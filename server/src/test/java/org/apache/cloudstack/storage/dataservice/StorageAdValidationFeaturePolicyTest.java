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

import java.util.Set;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageAdValidationFeaturePolicyTest {
    private JsonObject signed(){JsonObject proof=new JsonObject();proof.addProperty("readOnly",true);proof.addProperty("signedRuntimeVerified",true);proof.addProperty("nativeFileHashesVerified",true);JsonArray features=new JsonArray();features.add(StorageAdValidationFeaturePolicy.HANDLER);proof.add("signedSupportedFeatures",features);return proof;}
    private JsonObject nativeHandler(){JsonObject value=new JsonObject();value.addProperty("success",true);value.addProperty("sideEffects",false);value.addProperty("schemaVersion",1);value.addProperty("adIdentityHandlerSupported",true);value.addProperty("dependencyAvailable",true);value.addProperty("adIdentity",false);value.addProperty("productionAdIdentityVerified",false);value.add("missingExecutables",new JsonArray());JsonArray features=new JsonArray();features.add(StorageAdValidationFeaturePolicy.HANDLER);value.add("supportedFeatures",features);return value;}
    @Test public void scopedFixtureRequiresOnlySignedHandlerWithoutAdvertisingProductionOrChangingOtherFeatures(){JsonObject nativeProof=nativeHandler();Set<String> required=Set.of("NFS_VFS_POSIX_ACL","LOGICAL_RESOURCE_RESERVATION","SMB_ACTIVE_DIRECTORY","SMB_AD_IDENTITY","POSIX_AD_PRINCIPALS");Set<String> scoped=StorageAdValidationFeaturePolicy.scoped(required,signed(),nativeProof);Assert.assertEquals(Set.of("NFS_VFS_POSIX_ACL","LOGICAL_RESOURCE_RESERVATION",StorageAdValidationFeaturePolicy.HANDLER),scoped);Assert.assertTrue(required.contains("SMB_ACTIVE_DIRECTORY"));Assert.assertFalse(nativeProof.get("adIdentity").getAsBoolean());Assert.assertFalse(nativeProof.get("productionAdIdentityVerified").getAsBoolean());}
    @Test public void missingSignedHandlerCannotUseAClaimedNativeAvailability(){JsonObject proof=signed();proof.remove("signedSupportedFeatures");Assert.assertThrows(CloudRuntimeException.class,()->StorageAdValidationFeaturePolicy.scoped(Set.of("SMB_ACTIVE_DIRECTORY"),proof,nativeHandler()));}
    @Test public void unavailableDependencyAndStringBooleansCannotEnableFixture(){JsonObject nativeProof=nativeHandler();nativeProof.addProperty("dependencyAvailable",false);Assert.assertThrows(CloudRuntimeException.class,()->StorageAdValidationFeaturePolicy.scoped(Set.of("SMB_AD_IDENTITY"),signed(),nativeProof));nativeProof.addProperty("dependencyAvailable",true);nativeProof.addProperty("adIdentityHandlerSupported","true");Assert.assertThrows(CloudRuntimeException.class,()->StorageAdValidationFeaturePolicy.scoped(Set.of("SMB_AD_IDENTITY"),signed(),nativeProof));}
    @Test public void missingExecutableListRejectsEvenWhenAvailabilityBooleanClaimsTrue(){JsonObject nativeProof=nativeHandler();nativeProof.getAsJsonArray("missingExecutables").add("wbinfo");Assert.assertThrows(CloudRuntimeException.class,()->StorageAdValidationFeaturePolicy.scoped(Set.of("POSIX_AD_PRINCIPALS"),signed(),nativeProof));}
    @Test public void nonAdFeatureSetNeverNeedsAdHandlerOrProofBeforeItsInstallation(){Assert.assertEquals(Set.of("LOGICAL_RESOURCE_RESERVATION"),StorageAdValidationFeaturePolicy.scoped(Set.of("LOGICAL_RESOURCE_RESERVATION"),new JsonObject(),new JsonObject()));Assert.assertThrows(CloudRuntimeException.class,()->StorageAdValidationFeaturePolicy.scoped(null,signed(),nativeHandler()));}
}

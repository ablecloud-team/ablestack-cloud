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
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

public class StorageRetainedRootAuthorizationTest {
    private JsonObject scope() {JsonObject s=new JsonObject();s.addProperty("instanceUuid","11111111-1111-1111-1111-111111111111");s.addProperty("operationUuid","22222222-2222-2222-2222-222222222222");s.addProperty("templateUpgradeUuid","33333333-3333-3333-3333-333333333333");s.addProperty("revision",11);return s;}
    private JsonObject generation() {JsonObject g=new JsonObject();g.add("instanceUuid",scope().get("instanceUuid"));g.addProperty("revision",4);g.addProperty("configurationSha256","a".repeat(64));return g;}
    private JsonObject ref() {JsonObject r=new JsonObject();r.addProperty("authorizationUuid","44444444-4444-4444-4444-444444444444");r.addProperty("sha256","b".repeat(64));return r;}
    private JsonObject captured() {JsonObject r=new JsonObject();r.addProperty("success",true);r.addProperty("retainedBaselineCaptured",true);r.add("scope",scope());r.add("retainedGeneration",generation());r.addProperty("retainedRenderedSha256","c".repeat(64));r.add("baselineRef",ref());r.addProperty("canonicalDesiredStateChanged",false);r.addProperty("dataPermissionsChanged",false);return r;}
    @Test public void capturePinsTheActualOldGenerationAndPointerWithoutAPlainLatestSnapshot() {
        JsonObject request=StorageRetainedRootAuthorization.captureRequest(scope(),generation(),"c".repeat(64));Assert.assertEquals(Set.of("instanceUuid","operationUuid","templateUpgradeUuid","revision","expectedRetainedGeneration","expectedRetainedRenderedSha256"),request.keySet());Assert.assertEquals(ref(),StorageRetainedRootAuthorization.requireCaptured(scope(),generation(),"c".repeat(64),captured()));
        JsonObject wrong=captured();wrong.getAsJsonObject("retainedGeneration").addProperty("revision",5);JsonObject drift=wrong;Assert.assertThrows(RuntimeException.class,()->StorageRetainedRootAuthorization.requireCaptured(scope(),generation(),"c".repeat(64),drift));
        wrong=captured();wrong.addProperty("success","true");JsonObject stringBool=wrong;Assert.assertThrows(RuntimeException.class,()->StorageRetainedRootAuthorization.requireCaptured(scope(),generation(),"c".repeat(64),stringBool));
    }
    @Test public void authorizeTransportContainsOnlySealedCapsuleScopeReferencesAndFileBindings() {
        JsonObject capsule=new JsonObject();capsule.addProperty("ciphertext","sealed-only");JsonArray files=new JsonArray();JsonObject volume=new JsonObject();volume.addProperty("volumeUuid","data");volume.addProperty("sizeBytes",20L<<30);volume.addProperty("filesystemUuid","fs");files.add(volume);
        try(org.mockito.MockedStatic<com.cloud.utils.crypt.DBEncryptionUtil> crypto=Mockito.mockStatic(com.cloud.utils.crypt.DBEncryptionUtil.class)){crypto.when(()->com.cloud.utils.crypt.DBEncryptionUtil.decrypt("encrypted-test-key")).thenReturn("RAM-ONLY-PRIVATE-KEY");JsonObject request=StorageRetainedRootAuthorization.authorizeRequest(scope(),ref(),capsule,"encrypted-test-key".getBytes(java.nio.charset.StandardCharsets.UTF_8),scope(),"d".repeat(64),files);Assert.assertEquals(Set.of("instanceUuid","operationUuid","templateUpgradeUuid","revision","capsule","credentialPrivateKey","baselineRef","originalSourceScope","sourceConfigurationSha256","fileVolumeBindings"),request.keySet());Assert.assertEquals(capsule,request.get("capsule"));Assert.assertFalse(request.has("configurationDesiredState"));Assert.assertFalse(request.has("latest7"));}
        JsonObject foreign=scope();foreign.addProperty("instanceUuid","55555555-5555-5555-5555-555555555555");Assert.assertThrows(RuntimeException.class,()->StorageRetainedRootAuthorization.authorizeRequest(scope(),ref(),capsule,new byte[0],foreign,"d".repeat(64),files));
    }
    @Test public void latestIdentityHashAndOldBaselineCannotBeInterchangedOrCoerced() {
        JsonObject response=captured();response.remove("retainedBaselineCaptured");response.remove("baselineRef");response.addProperty("retainedRootAuthorized",true);response.addProperty("latestConfigurationSha256","d".repeat(64));response.addProperty("authorizationUuid",ref().get("authorizationUuid").getAsString());response.addProperty("sha256","b".repeat(64));response.add("retainedRootAuthorization",ref());Assert.assertEquals(ref(),StorageRetainedRootAuthorization.requireAuthorized(scope(),generation(),"c".repeat(64),"d".repeat(64),response));
        Assert.assertThrows(RuntimeException.class,()->StorageRetainedRootAuthorization.requireAuthorized(scope(),generation(),"c".repeat(64),"a".repeat(64),response));
        JsonObject wrong=response.deepCopy();wrong.getAsJsonObject("retainedRootAuthorization").addProperty("sha256","e".repeat(64));JsonObject nested=wrong;Assert.assertThrows(RuntimeException.class,()->StorageRetainedRootAuthorization.requireAuthorized(scope(),generation(),"c".repeat(64),"d".repeat(64),nested));
        wrong=response.deepCopy();wrong.addProperty("dataPermissionsChanged",true);JsonObject changed=wrong;Assert.assertThrows(RuntimeException.class,()->StorageRetainedRootAuthorization.requireAuthorized(scope(),generation(),"c".repeat(64),"d".repeat(64),changed));
    }
}

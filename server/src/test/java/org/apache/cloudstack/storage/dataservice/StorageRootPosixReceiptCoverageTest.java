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
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageRootPosixReceiptCoverageTest {
    private JsonObject inspected() {
        JsonObject request=JsonParser.parseString("{\"uuid\":\"policy\",\"instanceUuid\":\"instance\",\"volumeUuid\":\"data\",\"revision\":2,\"volumeMountPath\":\"/export\",\"relativePath\":\"child\",\"expectedDirectoryIdentity\":{\"effectiveUid\":65534}}").getAsJsonObject();
        JsonObject post=JsonParser.parseString("{\"filesystemUuid\":\"fs\",\"device\":2048,\"inode\":12,\"effectiveUid\":0,\"effectiveGid\":0,\"effectiveMode\":\"0770\",\"aclSha256\":\"hash\"}").getAsJsonObject();
        JsonObject effective=new JsonObject();effective.add("directoryIdentity",post.deepCopy());JsonObject previous=new JsonObject();previous.add("request",request);previous.add("config",new JsonObject());previous.add("effective",effective);
        JsonObject scope=new JsonObject();for(String field:List.of("instanceUuid","volumeUuid","revision","volumeMountPath","relativePath"))scope.add(field,request.get(field));scope.add("policyUuid",request.get("uuid"));
        JsonObject receipt=new JsonObject();receipt.addProperty("phase","COMPLETE");receipt.add("scope",scope);receipt.add("directoryIdentity",post.deepCopy());
        JsonObject inspected=new JsonObject();inspected.addProperty("postApplyReceiptSupported",true);inspected.addProperty("postApplyReceiptVerified",true);inspected.add("previousPolicy",previous);inspected.add("previousPostApplyReceipt",receipt);inspected.add("directoryIdentity",post.deepCopy());for(String field:List.of("uuid","instanceUuid","volumeUuid","revision","volumeMountPath","relativePath"))inspected.add(field,request.get(field));return inspected;
    }
    @Test public void verifiedPostReceiptKeepsOriginalPreapplyGuardWithoutImplicitReapply() {
        JsonObject inspect=inspected(),canonical=inspect.getAsJsonObject("previousPolicy").deepCopy();new StorageServiceManagerImpl().requireRootPosixReceipt(canonical,inspect);
        Assert.assertEquals(65534,canonical.getAsJsonObject("request").getAsJsonObject("expectedDirectoryIdentity").get("effectiveUid").getAsInt());Assert.assertEquals(0,inspect.getAsJsonObject("directoryIdentity").get("effectiveUid").getAsInt());
    }
    @Test public void legacyMissingAndStaleOrForeignPostReceiptBlockBeforeRootAllocation() {
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();JsonObject inspect=inspected(),canonical=inspect.getAsJsonObject("previousPolicy").deepCopy();inspect.remove("previousPostApplyReceipt");Assert.assertThrows(CloudRuntimeException.class,()->manager.requireRootPosixReceipt(canonical,inspect));
        JsonObject stale=inspected();stale.getAsJsonObject("directoryIdentity").addProperty("inode",13);Assert.assertThrows(CloudRuntimeException.class,()->manager.requireRootPosixReceipt(canonical,stale));
        JsonObject foreign=inspected();foreign.getAsJsonObject("previousPostApplyReceipt").getAsJsonObject("scope").addProperty("volumeUuid","foreign");Assert.assertThrows(CloudRuntimeException.class,()->manager.requireRootPosixReceipt(canonical,foreign));
        JsonObject string=inspected();string.addProperty("postApplyReceiptSupported","true");Assert.assertThrows(CloudRuntimeException.class,()->manager.requireRootPosixReceipt(canonical,string));
    }
    @Test public void onlyProtectedDeviceRemapPreservesCanonicalBytesAcrossSuccessiveRootBoots() {
        JsonObject inspect=inspected(),canonical=inspect.getAsJsonObject("previousPolicy").deepCopy(),receipt=inspect.getAsJsonObject("previousPostApplyReceipt");
        receipt.add("canonicalDirectoryIdentity",inspect.get("directoryIdentity").deepCopy());inspect.getAsJsonObject("directoryIdentity").addProperty("device",2064);receipt.getAsJsonObject("directoryIdentity").addProperty("device",2064);
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();Assert.assertThrows(CloudRuntimeException.class,()->manager.requireRootPosixReceipt(canonical,inspect));
        receipt.add("rootScope",JsonParser.parseString("{\"instanceUuid\":\"instance\",\"operationUuid\":\"root-op\",\"templateUpgradeUuid\":\"root-upgrade\",\"revision\":3}"));receipt.addProperty("sourceConfigurationSha256","a".repeat(64));manager.requireRootPosixReceipt(canonical,inspect);
        Assert.assertEquals(2048,canonical.getAsJsonObject("effective").getAsJsonObject("directoryIdentity").get("device").getAsInt());
        inspect.getAsJsonObject("directoryIdentity").addProperty("effectiveMode","0777");receipt.getAsJsonObject("directoryIdentity").addProperty("effectiveMode","0777");Assert.assertThrows(CloudRuntimeException.class,()->manager.requireRootPosixReceipt(canonical,inspect));
    }

}

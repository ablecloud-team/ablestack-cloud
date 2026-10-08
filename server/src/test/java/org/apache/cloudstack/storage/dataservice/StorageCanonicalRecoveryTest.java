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
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;
public class StorageCanonicalRecoveryTest {
    private JsonObject desired(String state) {JsonObject file=new JsonObject();JsonArray acls=new JsonArray();JsonObject acl=new JsonObject();acl.addProperty("state",state);acl.addProperty("principal","same-user");acl.addProperty("permission","RW");acls.add(acl);file.add("acls",acls);JsonObject result=new JsonObject();result.add("desired-state/smb-share-apply.json",file);result.add("desired-state/nfs-export-apply.json",JsonNull.INSTANCE);return result;}
    @Test public void acceptedLifecycleStateHasSameAppliedMeaningWithoutWeakeningAclSemantics() {StorageCanonicalRecovery.requireSameMeaning(desired("Updating"),desired("Ready"));JsonObject different=desired("Ready");different.getAsJsonObject("desired-state/smb-share-apply.json").getAsJsonArray("acls").get(0).getAsJsonObject().addProperty("permission","RO");Assert.assertThrows(CloudRuntimeException.class,()->StorageCanonicalRecovery.requireSameMeaning(desired("Updating"),different));Assert.assertThrows(CloudRuntimeException.class,()->StorageCanonicalRecovery.requireSameMeaning(desired("Ready"),desired("Disabled")));}
    @Test public void absentFilesCannotBeSilentlyReplacedByEmptyDefaults() {JsonObject empty=desired("Ready");JsonObject nfs=new JsonObject();nfs.addProperty("enabled",true);nfs.add("exports",new JsonArray());empty.add("desired-state/nfs-export-apply.json",nfs);Assert.assertThrows(CloudRuntimeException.class,()->StorageCanonicalRecovery.requireSameMeaning(desired("Ready"),empty));Assert.assertTrue(StorageCanonicalRecovery.emptyProtocolFile(StorageServiceInstance.Protocol.NFS,nfs));nfs.getAsJsonArray("exports").add(new JsonObject());Assert.assertFalse(StorageCanonicalRecovery.emptyProtocolFile(StorageServiceInstance.Protocol.NFS,nfs));}
    @Test public void networkPrimaryAndBackingPathChangesRemainSemanticDifferences() {JsonObject source=desired("Ready");JsonObject actual=source.deepCopy();JsonObject network=new JsonObject();network.addProperty("primaryIp","10.10.13.240");source.add("network-endpoints.json",network);actual.add("network-endpoints.json",network.deepCopy());actual.getAsJsonObject("network-endpoints.json").addProperty("primaryIp","10.10.13.241");Assert.assertThrows(CloudRuntimeException.class,()->StorageCanonicalRecovery.requireSameMeaning(source,actual));}
}

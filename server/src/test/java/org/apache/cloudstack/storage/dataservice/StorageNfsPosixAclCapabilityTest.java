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
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageNfsPosixAclCapabilityTest {
    private JsonObject proof() {
        JsonObject p=new JsonObject();p.addProperty("success",true);p.addProperty("nfsVfsPosixAclSupported",true);p.addProperty("posixAclBuildEnabled",true);p.addProperty("posixAclSelfTestVerified",true);
        p.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);p.addProperty("ganeshaVersion","5.5.3");p.addProperty("ganeshaBuildManifestSha256","a".repeat(64));p.addProperty("vfsLibrarySha256","b".repeat(64));JsonArray features=new JsonArray();features.add("NFS_VFS_POSIX_ACL");p.add("supportedFeatures",features);return p;
    }
    @Test public void versionOrLinkedLibaclCannotReplaceProtectedBinaryBuildAndRealAccessProof() {
        JsonObject p=proof();Assert.assertTrue(StorageNfsPosixAclCapability.supported(p,System.currentTimeMillis()));
        for(String field:List.of("ganeshaBuildManifestSha256","vfsLibrarySha256","posixAclBuildEnabled","posixAclSelfTestVerified","supportedFeatures")) {JsonObject missing=p.deepCopy();missing.remove(field);Assert.assertFalse(field,StorageNfsPosixAclCapability.supported(missing,System.currentTimeMillis()));}
        p.addProperty("ganeshaVersion","4.3-2");Assert.assertFalse(StorageNfsPosixAclCapability.supported(p,System.currentTimeMillis()));
    }
    @Test public void staleMetricsAndStringBooleansCannotAuthorizeNamedAclBinding() {
        JsonObject stale=proof();stale.addProperty("generatedEpoch",1);Assert.assertFalse(StorageNfsPosixAclCapability.supported(stale,System.currentTimeMillis()));
        for(String field:List.of("success","nfsVfsPosixAclSupported","posixAclBuildEnabled","posixAclSelfTestVerified")) {JsonObject p=proof();p.addProperty(field,"true");Assert.assertFalse(field,StorageNfsPosixAclCapability.supported(p,System.currentTimeMillis()));}
        JsonObject epoch=proof();epoch.addProperty("generatedEpoch",String.valueOf(System.currentTimeMillis()/1000.0));Assert.assertFalse(StorageNfsPosixAclCapability.supported(epoch,System.currentTimeMillis()));
    }
    @Test public void ownerModeOnlyDoesNotRequireNamedAclButAccessAndDefaultEntriesDo() {
        JsonObject config=new JsonObject(),effective=new JsonObject();Assert.assertFalse(StorageNfsPosixAclCapability.named(config,effective));
        effective.add("acl",JsonParser.parseString("[\"user::rwx\",\"group::rwx\",\"other::---\"]"));Assert.assertFalse(StorageNfsPosixAclCapability.named(config,effective));
        effective.getAsJsonArray("acl").add("default:group:1002:rwx");Assert.assertTrue(StorageNfsPosixAclCapability.named(config,effective));
        config.add("accessEntries",JsonParser.parseString("[{\"principalType\":\"LOCAL_USER\",\"principal\":\"named\",\"permission\":\"READ_WRITE\"}]"));Assert.assertTrue(StorageNfsPosixAclCapability.named(config,new JsonObject()));
    }
    @Test public void parentNfsExportRequiresCapabilityForChildSambaNamedPolicyOnSameVolumeOnly() {
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(7L);
        StorageFileShareVO share=Mockito.mock(StorageFileShareVO.class);Mockito.when(share.getProtocol()).thenReturn(StorageServiceInstance.Protocol.NFS);Mockito.when(share.getVolumeId()).thenReturn(1000L);Mockito.when(share.getConfigJson()).thenReturn("{\"relativeSharePath\":\"parent\"}");
        StoragePosixDirectoryPolicyVO child=new StoragePosixDirectoryPolicyVO();child.setVolumeId(1000L);child.setRelativePath("parent/smb-child");child.setState("Ready");child.setConfigJson("{\"accessEntries\":[{\"principalType\":\"LOCAL_USER\",\"principal\":\"named\",\"permission\":\"READ_WRITE\"}]}");
        org.apache.cloudstack.storage.dataservice.dao.StoragePosixDirectoryPolicyDao policies=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StoragePosixDirectoryPolicyDao.class);Mockito.when(policies.listByInstance(7L)).thenReturn(List.of(child));ReflectionTestUtils.setField(manager,"storagePosixPolicyDao",policies);
        Assert.assertTrue(manager.nfsShareHasNamedPolicy(instance,share));child.setVolumeId(1001L);Assert.assertFalse(manager.nfsShareHasNamedPolicy(instance,share));child.setVolumeId(1000L);child.setRelativePath("other/smb-child");Assert.assertFalse(manager.nfsShareHasNamedPolicy(instance,share));
        Mockito.when(share.getProtocol()).thenReturn(StorageServiceInstance.Protocol.SMB);Assert.assertFalse(manager.nfsShareHasNamedPolicy(instance,share));
    }
}

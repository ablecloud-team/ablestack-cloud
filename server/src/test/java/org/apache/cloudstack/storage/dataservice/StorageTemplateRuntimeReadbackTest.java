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
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import com.google.gson.JsonObject;
import com.cloud.utils.exception.CloudRuntimeException;
public class StorageTemplateRuntimeReadbackTest {
    private final StorageServiceRuntimeUpgradeManagerImpl manager=new StorageServiceRuntimeUpgradeManagerImpl();
    private StorageServiceRuntimeBundleVO bundle(){StorageServiceRuntimeBundleVO row=Mockito.mock(StorageServiceRuntimeBundleVO.class);Mockito.when(row.getVersion()).thenReturn("verified-source");Mockito.when(row.getSha256()).thenReturn("archive-hash");Mockito.when(row.getManifestSha256()).thenReturn("manifest-hash");return row;}
    private JsonObject proof(){JsonObject result=new JsonObject();for(String key:new String[]{"success","signedRuntimeVerified","installedFilesVerified","entrypointsVerified"}) result.addProperty(key,true);result.addProperty("currentVersion","verified-source");result.addProperty("archiveSha256","archive-hash");result.addProperty("manifestSha256","manifest-hash");return result;}
    @Test public void installedCodeRequiresSignedManifestEveryFileAndEntrypointBinding(){Assert.assertEquals("verified-source",manager.requireRuntimeReadback(proof(),bundle()).get("currentVersion").getAsString());}
    @Test public void versionOrManifestOrArchiveMismatchCannotRetainACurrentBundleProjection(){
        for(String key:new String[]{"currentVersion","archiveSha256","manifestSha256"}) {JsonObject proof=proof();proof.addProperty(key,"other");Assert.assertThrows(CloudRuntimeException.class,()->manager.requireRuntimeReadback(proof,bundle()));}
    }
    @Test public void sourceVersionAloneCannotStandInForActualSignatureFileAndSymlinkChecks(){
        for(String key:new String[]{"signedRuntimeVerified","installedFilesVerified","entrypointsVerified"}) {JsonObject proof=proof();proof.addProperty(key,false);Assert.assertThrows(CloudRuntimeException.class,()->manager.requireRuntimeReadback(proof,bundle()));}
        Assert.assertThrows(CloudRuntimeException.class,()->manager.requireRuntimeReadback(new JsonObject(),bundle()));
    }
}

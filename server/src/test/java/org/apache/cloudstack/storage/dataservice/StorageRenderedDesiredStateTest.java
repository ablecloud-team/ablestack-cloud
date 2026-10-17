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

import java.util.EnumMap;
import java.util.Map;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.exception.InvalidParameterValueException;

public class StorageRenderedDesiredStateTest {
    private JsonObject source() {JsonObject value=new JsonObject();for(String path:StorageRenderedDesiredState.PATHS)value.add(path,JsonNull.INSTANCE);return value;}
    private Map<StorageServiceInstance.Protocol,JsonObject> protocols() {Map<StorageServiceInstance.Protocol,JsonObject> values=new EnumMap<>(StorageServiceInstance.Protocol.class);for(StorageServiceInstance.Protocol p:StorageServiceInstance.Protocol.values())values.put(p,null);return values;}
    @Test public void neverEnabledProtocolsRemainNullAndNativeFourKeysHaveNoExtraWrapper() {
        JsonObject candidate=StorageRenderedDesiredState.candidate(source(),protocols()),payload=StorageRenderedDesiredState.protocols(candidate);
        Assert.assertEquals(java.util.Set.of("NFS","SMB","ISCSI","NVMEOF"),payload.keySet());for(String key:payload.keySet())Assert.assertTrue(payload.get(key).isJsonNull());
    }
    @Test public void unchangedNetworkPosixAndApprovedBeforeGuardRemainExactWithoutSourceMutation() {
        JsonObject source=source();source.add("posix-directory-policies.json",JsonParser.parseString("{\"policy\":{\"request\":{\"expectedDirectoryIdentity\":{\"effectiveUid\":65534,\"device\":2048}},\"effective\":{\"effectiveUid\":0,\"device\":2048}}}"));source.add("network-endpoints.json",JsonParser.parseString("{\"primaryIp\":\"10.10.13.240\",\"endpoints\":[{\"listenIp\":\"10.10.13.241\",\"macAddress\":\"02:01:00:00:00:01\"}]}"));
        JsonObject frozen=source.deepCopy(),candidate=StorageRenderedDesiredState.candidate(source,protocols());Assert.assertEquals(frozen,source);Assert.assertEquals(frozen.get("posix-directory-policies.json"),candidate.get("posix-directory-policies.json"));Assert.assertEquals(frozen.get("network-endpoints.json"),candidate.get("network-endpoints.json"));
        candidate.getAsJsonObject("posix-directory-policies.json").addProperty("mutated",true);Assert.assertEquals(frozen,source);
    }
    @Test public void nestedTransientCredentialsAreRemovedFromPublicGenerationWithoutMutatingHeapPayload() {
        JsonObject raw=JsonParser.parseString("{\"enabled\":true,\"generatedEpoch\":123,\"shares\":[{\"uuid\":\"share\",\"acls\":[{\"password\":\"synthetic\",\"config\":{\"mutualChapSecret\":\"synthetic\",\"DHCHAPKEY\":\"synthetic\",\"credentialPrivateKey\":\"synthetic\",\"keytab\":\"synthetic\",\"capsuleSha256\":\"synthetic\",\"principal\":\"managed\"}}]}]}").getAsJsonObject();JsonObject frozen=raw.deepCopy();Map<StorageServiceInstance.Protocol,JsonObject> values=protocols();values.put(StorageServiceInstance.Protocol.SMB,raw);
        JsonObject candidate=StorageRenderedDesiredState.candidate(source(),values);Assert.assertFalse(candidate.toString().contains("synthetic"));Assert.assertFalse(candidate.toString().contains("generatedEpoch"));Assert.assertEquals(frozen,raw);Assert.assertEquals(candidate.get("desired-state/smb-share-apply.json"),StorageRenderedDesiredState.protocols(candidate).get("SMB"));
    }
    @Test public void incompleteOrForeignCanonicalFileSetsCannotEnterNativeStage() {
        JsonObject missing=source();missing.remove("network-endpoints.json");Assert.assertThrows(InvalidParameterValueException.class,()->StorageRenderedDesiredState.candidate(missing,protocols()));JsonObject extra=source();extra.add("foreign.json",new JsonObject());Assert.assertThrows(InvalidParameterValueException.class,()->StorageRenderedDesiredState.candidate(extra,protocols()));Map<StorageServiceInstance.Protocol,JsonObject> partial=protocols();partial.remove(StorageServiceInstance.Protocol.ISCSI);Assert.assertThrows(InvalidParameterValueException.class,()->StorageRenderedDesiredState.candidate(source(),partial));
    }
}

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

import java.util.LinkedHashMap;
import java.util.Map;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;

public class StorageConfigEndpointMappingTest {
    private Map<String, JsonArray> resources(String json) {
        Map<String, JsonArray> rows = new LinkedHashMap<>();rows.put("protocols", JsonParser.parseString(json).getAsJsonArray());return rows;
    }
    private JsonObject mapping(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private final String source = "[{\"uuid\":\"11111111-1111-4111-8111-111111111111\",\"listen_ip\":\"10.10.13.223\","
            + "\"config\":{\"listenIps\":[\"10.10.13.223\"],\"allowHosts\":[\"10.10.13.223\"]}}]";

    @Test public void foreignCloneCannotSilentlyBorrowSourceListener() {
        Map<String, JsonArray> rows = resources(source);JsonArray blockers = new JsonArray();
        JsonObject result = StorageConfigEndpointMapping.apply(rows, new JsonObject(), true, blockers);
        Assert.assertEquals(1, blockers.size());Assert.assertTrue(blockers.get(0).getAsString().startsWith("LISTENER_MAPPING_REQUIRED:"));
        Assert.assertEquals("10.10.13.223", rows.get("protocols").get(0).getAsJsonObject().get("listen_ip").getAsString());
        Assert.assertEquals(2, result.getAsJsonArray("listenerAddressRequirements").get(0).getAsJsonObject().getAsJsonArray("locations").size());
    }
    @Test public void reviewedPrimaryUsesExactTargetAndLeavesAclUntouched() {
        Map<String, JsonArray> rows = resources(source);JsonArray blockers = new JsonArray();
        StorageConfigEndpointMapping.apply(rows, mapping("{\"listenerAddresses\":{\"10.10.13.223\":\"PRIMARY\"}}"), true, blockers, "10.10.13.224");
        JsonObject row = rows.get("protocols").get(0).getAsJsonObject();
        Assert.assertEquals(0, blockers.size());Assert.assertEquals("10.10.13.224", row.get("listen_ip").getAsString());
        Assert.assertEquals("10.10.13.224", row.getAsJsonObject("config").getAsJsonArray("listenIps").get(0).getAsString());
        Assert.assertEquals("10.10.13.223", row.getAsJsonObject("config").getAsJsonArray("allowHosts").get(0).getAsString());
    }
    @Test public void reviewedExplicitTargetIsTheOnlyAddressRewritten() {
        Map<String, JsonArray> rows = resources(source);JsonArray blockers = new JsonArray();
        StorageConfigEndpointMapping.apply(rows, mapping("{\"listenerAddresses\":{\"10.10.13.223\":\"10.10.13.224\"}}"), true, blockers);
        Assert.assertEquals(0, blockers.size());JsonObject row = rows.get("protocols").get(0).getAsJsonObject();
        Assert.assertEquals("10.10.13.224", row.get("listen_ip").getAsString());
        Assert.assertEquals("10.10.13.224", row.getAsJsonObject("config").getAsJsonArray("listenIps").get(0).getAsString());
    }
    @Test public void sameServiceMayRetainAddressesWithoutNewMapping() {
        JsonArray blockers = new JsonArray();StorageConfigEndpointMapping.apply(resources(source), new JsonObject(), false, blockers);
        Assert.assertEquals(0, blockers.size());
    }
    @Test public void nullListenersRemainUnchangedWithoutBroadening() {
        JsonArray blockers = new JsonArray();JsonObject result = StorageConfigEndpointMapping.apply(
                resources("[{\"uuid\":\"11111111-1111-4111-8111-111111111111\",\"listen_ip\":null,\"config\":{}}]"),
                new JsonObject(), true, blockers);
        Assert.assertEquals(0, blockers.size());Assert.assertEquals(0, result.getAsJsonArray("listenerAddressRequirements").size());
    }
    @Test public void mixedPrimaryAndExplicitListNeedsKnownStaticPrimary() {
        String mixed = source.replace("[\"10.10.13.223\"]", "[\"10.10.13.223\",\"10.10.13.225\"]");
        JsonObject request = mapping("{\"listenerAddresses\":{\"10.10.13.223\":\"PRIMARY\",\"10.10.13.225\":\"10.10.13.226\"}}");
        JsonArray blocked = new JsonArray();StorageConfigEndpointMapping.apply(resources(mixed), request, true, blocked);Assert.assertEquals(1, blocked.size());
        request.add("createNew", mapping("{\"networkmode\":\"STATIC\",\"ipcidr\":\"10.10.13.224/16\"}"));
        JsonArray blockers = new JsonArray();Map<String, JsonArray> rows = resources(mixed);
        StorageConfigEndpointMapping.apply(rows, request, true, blockers);Assert.assertEquals(0, blockers.size());
        Assert.assertEquals(2, rows.get("protocols").get(0).getAsJsonObject().getAsJsonObject("config").getAsJsonArray("listenIps").size());
    }
    @Test public void primaryWithoutKnownAddressIsBlockedAndNeverBecomesWildcard() {
        Map<String, JsonArray> rows = resources(source);JsonArray blockers = new JsonArray();
        StorageConfigEndpointMapping.apply(rows, mapping("{\"listenerAddresses\":{\"10.10.13.223\":\"PRIMARY\"}}"), true, blockers);
        Assert.assertEquals(1, blockers.size());
        Assert.assertEquals("PRIMARY_LISTENER_REQUIRES_STATIC_ADDRESS", blockers.get(0).getAsString());
        Assert.assertEquals("10.10.13.223", rows.get("protocols").get(0).getAsJsonObject().get("listen_ip").getAsString());
    }
    @Test(expected = CloudRuntimeException.class) public void unknownSourceMappingIsRejected() {
        StorageConfigEndpointMapping.apply(resources(source), mapping("{\"listenerAddresses\":{\"10.10.13.222\":\"PRIMARY\"}}"), true, new JsonArray());
    }
    @Test(expected = CloudRuntimeException.class) public void hostnameTargetIsRejected() {
        StorageConfigEndpointMapping.apply(resources(source), mapping("{\"listenerAddresses\":{\"10.10.13.223\":\"example.local\"}}"), true, new JsonArray());
    }
    @Test(expected = CloudRuntimeException.class) public void nonStringPrimaryIsRejected() {
        StorageConfigEndpointMapping.apply(resources(source), mapping("{\"listenerAddresses\":{\"10.10.13.223\":true}}"), true, new JsonArray());
    }
}

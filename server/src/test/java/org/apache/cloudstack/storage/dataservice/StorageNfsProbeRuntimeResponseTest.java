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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/** Public API transport contracts; native process/inventory authority remains with the guest. */
public class StorageNfsProbeRuntimeResponseTest {
    private final StorageServiceManagerImpl manager = new StorageServiceManagerImpl();
    private final StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
    private final StorageServiceGuestCommandDispatcher guest = Mockito.mock(StorageServiceGuestCommandDispatcher.class);

    public StorageNfsProbeRuntimeResponseTest() {
        Mockito.when(instance.getVmId()).thenReturn(431L);
        Mockito.when(instance.getUuid()).thenReturn("9a656b8b-8441-4944-a573-44575223d208");
        ReflectionTestUtils.setField(manager, "guestCommandDispatcher", guest);
    }

    private JsonObject response(JsonObject value, boolean success) {
        Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(success, "public runtime", value.toString()));
        StorageServiceRuntimeResponse result = manager.createRuntimeResponse(instance, "health", "");
        JsonObject response = new Gson().toJsonTree(result).getAsJsonObject();
        Mockito.verify(guest, Mockito.atLeastOnce()).dispatch(Mockito.argThat(command ->
                command.getVmId() == 431L && "health".equals(command.getOperation()) && "".equals(command.getPayload())));
        return response;
    }

    private JsonObject runtime(JsonObject response) {
        return JsonParser.parseString(response.get("resultjson").getAsString()).getAsJsonObject();
    }

    private JsonObject probe(String status, String reason) {
        JsonObject probe = new JsonObject();
        probe.addProperty("exportUuid", "a011f6a6-b0c0-45f0-a503-22ec7a54474f");
        probe.addProperty("exportName", "restricted");
        probe.addProperty("protocolMode", "V3V4_DUAL");
        probe.addProperty("targetPath", "/srv/ablestack-storage/volumes/volume/restricted");
        probe.addProperty("pseudoPath", "/restricted");
        probe.addProperty("success", true);
        probe.addProperty("status", status);
        probe.addProperty("probeSkipped", true);
        probe.addProperty("skipReason", reason);
        probe.add("probes", new JsonArray());
        JsonArray clients = new JsonArray();
        clients.add("192.0.2.25/32");
        probe.add("clients", clients);
        return probe;
    }

    private JsonObject health(JsonObject probe) {
        JsonObject endpoint = new JsonObject();
        endpoint.addProperty("listenIp", "0.0.0.0");
        endpoint.addProperty("port", 2049);
        endpoint.addProperty("listening", true);
        endpoint.addProperty("processRunning", true);
        endpoint.addProperty("probeObservationVerified", true);
        endpoint.addProperty("listenerOwned", true);
        endpoint.addProperty("exportInventoryReady", true);
        endpoint.addProperty("probeSuccess", true);
        endpoint.addProperty("probeSkipped", true);
        endpoint.add("exportsVisible", JsonNull.INSTANCE);
        endpoint.addProperty("probeSkipReason", probe.get("skipReason").getAsString());
        JsonArray probes = new JsonArray();
        probes.add(probe);
        endpoint.add("probeResults", probes);
        JsonArray endpoints = new JsonArray();
        endpoints.add(endpoint);
        JsonObject nfs = new JsonObject();
        nfs.addProperty("status", "ok");
        nfs.add("endpoints", endpoints);
        JsonObject health = new JsonObject();
        health.addProperty("success", true);
        health.addProperty("status", "ok");
        health.addProperty("generatedEpoch", 1792224000.125);
        health.addProperty("bootId", "07070ba6-70e0-49b4-94e3-44c9c1cd4586");
        health.add("nfsGanesha", nfs);
        return health;
    }

    @Test public void restrictedAclSkipReasonAndUnknownMountVisibilitySurviveThePublicApiUnchanged() {
        JsonObject expected = health(probe("SKIPPED_ACL", "ACL_RESTRICTED_NO_LOCAL_SOURCE"));
        JsonObject response = response(expected, true);
        Assert.assertTrue(response.get("success").getAsBoolean());
        Assert.assertEquals("ok", response.get("status").getAsString());
        Assert.assertEquals(expected, runtime(response));
        JsonObject observed = runtime(response).getAsJsonObject("nfsGanesha").getAsJsonArray("endpoints").get(0).getAsJsonObject();
        Assert.assertTrue(observed.get("exportsVisible").isJsonNull());
        Assert.assertEquals("ACL_RESTRICTED_NO_LOCAL_SOURCE", observed.getAsJsonArray("probeResults").get(0)
                .getAsJsonObject().get("skipReason").getAsString());
    }

    @Test public void verifiedDynamicSkipRemainsDistinctFromAclSkipAndFromMountedVisibility() {
        JsonObject expected = health(probe("SKIPPED_DYNAMIC", "DBUS_DYNAMIC_UPDATE_VERIFIED"));
        JsonObject nfs = expected.getAsJsonObject("nfsGanesha");
        nfs.addProperty("skippedDynamicCount", 1);
        nfs.addProperty("skippedAclCount", 0);
        nfs.addProperty("detailVerificationKind", "EXACT_RUNTIME_DYNAMIC_UPDATE");
        JsonObject observed = runtime(response(expected, true));
        Assert.assertEquals(expected, observed);
        Assert.assertEquals(0, observed.getAsJsonObject("nfsGanesha").getAsJsonArray("endpoints").get(0)
                .getAsJsonObject().getAsJsonArray("probeResults").get(0).getAsJsonObject().getAsJsonArray("probes").size());
    }

    @Test public void structuredRealFailureAndItsScopeAreNotConvertedToSuccessfulSkip() {
        JsonObject expected = health(probe("SKIPPED_ACL", "ACL_RESTRICTED_NO_LOCAL_SOURCE"));
        expected.addProperty("success", false);
        expected.addProperty("status", "degraded");
        JsonObject failed = new JsonObject();
        failed.addProperty("exportUuid", "721f30be-1911-4e58-96a4-c02c8ce2b1e0");
        failed.addProperty("success", false);
        failed.addProperty("status", "PROBE_PERMISSION_DENIED");
        failed.addProperty("probeSkipped", false);
        JsonObject failure = new JsonObject();
        failure.addProperty("code", "PROBE_PERMISSION_DENIED");
        failure.addProperty("protocol", "NFSv4");
        failure.addProperty("retryable", false);
        failed.add("failure", failure);
        expected.getAsJsonObject("nfsGanesha").getAsJsonArray("endpoints").get(0).getAsJsonObject()
                .getAsJsonArray("probeResults").add(failed);
        JsonObject response = response(expected, false);
        Assert.assertFalse(response.get("success").getAsBoolean());
        Assert.assertEquals("degraded", response.get("status").getAsString());
        Assert.assertEquals(expected, runtime(response));
    }

    @Test public void skipEvidenceDoesNotRelaxTheExistingRuntimePromotionGate() {
        StorageServiceRuntimeUpgradeManagerImpl upgrades = new StorageServiceRuntimeUpgradeManagerImpl();
        JsonObject value = health(probe("SKIPPED_ACL", "ACL_RESTRICTED_NO_LOCAL_SOURCE"));
        Assert.assertTrue(upgrades.runtimeHealthVerified(new StorageServiceGuestCommandResult(true, "public", value.toString())));
        value.addProperty("status", "degraded");
        Assert.assertFalse(upgrades.runtimeHealthVerified(new StorageServiceGuestCommandResult(true, "public", value.toString())));
        value.addProperty("status", "ok");
        value.addProperty("success", false);
        Assert.assertFalse(upgrades.runtimeHealthVerified(new StorageServiceGuestCommandResult(true, "public", value.toString())));
        value.addProperty("success", true);
        Assert.assertFalse(upgrades.runtimeHealthVerified(new StorageServiceGuestCommandResult(false, "public", value.toString())));
    }

    @Test public void nestedSecretsAreRedactedWithoutDroppingClientAclOrTheProbeReason() {
        JsonObject value = health(probe("SKIPPED_ACL", "ACL_RESTRICTED_NO_LOCAL_SOURCE"));
        JsonObject entry = value.getAsJsonObject("nfsGanesha").getAsJsonArray("endpoints").get(0).getAsJsonObject()
                .getAsJsonArray("probeResults").get(0).getAsJsonObject();
        entry.addProperty("password", "synthetic-not-an-operator-credential");
        entry.addProperty("privateKey", "synthetic-private");
        JsonObject client = new JsonObject();
        client.addProperty("clients", "192.0.2.25/32");
        client.addProperty("accessType", "RW");
        client.addProperty("secret", "synthetic-hidden");
        JsonArray clients = new JsonArray();
        clients.add(client);
        entry.add("clients", clients);
        JsonObject expected = value.deepCopy();
        JsonObject sanitized = expected.getAsJsonObject("nfsGanesha").getAsJsonArray("endpoints").get(0).getAsJsonObject()
                .getAsJsonArray("probeResults").get(0).getAsJsonObject();
        sanitized.remove("password");
        sanitized.remove("privateKey");
        sanitized.getAsJsonArray("clients").get(0).getAsJsonObject().remove("secret");
        Assert.assertEquals(expected, runtime(response(value, true)));
        Assert.assertTrue(entry.has("password"));
        Assert.assertTrue(client.has("secret"));
    }

    @Test public void guestObservationExceptionRemainsUnavailableRatherThanReusingEarlierSkipEvidence() {
        JsonObject previous = health(probe("SKIPPED_ACL", "ACL_RESTRICTED_NO_LOCAL_SOURCE"));
        Assert.assertEquals(previous, runtime(response(previous, true)));
        Mockito.when(guest.dispatch(Mockito.any())).thenThrow(new IllegalStateException("synthetic observation unavailable"));
        JsonObject unavailable = new Gson().toJsonTree(manager.createRuntimeResponse(instance, "health", "")).getAsJsonObject();
        Assert.assertFalse(unavailable.get("success").getAsBoolean());
        Assert.assertEquals("ERROR", unavailable.get("status").getAsString());
        Assert.assertEquals(new JsonObject(), runtime(unavailable));
    }
}

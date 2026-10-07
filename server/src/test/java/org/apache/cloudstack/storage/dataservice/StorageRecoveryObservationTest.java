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

import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class StorageRecoveryObservationTest {
    private JsonObject object(String json) { return new JsonParser().parse(json).getAsJsonObject(); }
    @Test public void rejectsStaleFutureAndMissingObservations() {
        for (String json : new String[] {"{}", "{'generatedEpoch':1}", "{'generatedEpoch':120}"}) {
            Assert.assertThrows(CloudRuntimeException.class, () -> StorageRecoveryObservation.requireFresh(object(json), 100));
        }
        StorageRecoveryObservation.requireFresh(object("{'generatedEpoch':80}"), 100);
    }
    @Test public void nfsOverridesAndShareReadOnlyRemainIndependent() {
        JsonObject expected = StorageRecoveryObservation.nfsClient("10.1.1.9/32", true,
                object("{'readOnly':true,'rootSquash':true,'anonUid':65534}"), object("{'allSquash':true,'anonUid':1002}"));
        Assert.assertEquals("RO", expected.get("access").getAsString());
        Assert.assertEquals("All_Squash", expected.get("squash").getAsString());
        Assert.assertEquals(1002, expected.get("anonUid").getAsInt());
        Assert.assertEquals(65534, expected.get("anonGid").getAsInt());
    }
    @Test public void nfsMissingOrBroaderClientPolicyCannotReconcile() {
        JsonArray expected = new JsonArray();expected.add(StorageRecoveryObservation.nfsClient("10.1.1.9/32", true, new JsonObject(), new JsonObject()));
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageRecoveryObservation.requireNfsClients(expected, object("{}")));
        JsonObject observed = new JsonObject();JsonArray actual = new JsonArray();
        actual.add(StorageRecoveryObservation.nfsClient("*", true, new JsonObject(), new JsonObject()));observed.add("clients", actual);
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageRecoveryObservation.requireNfsClients(expected, observed));
        observed.add("clients", expected);StorageRecoveryObservation.requireNfsClients(expected, observed);
    }
    @Test public void nfsRenderedSquashDriftCannotReconcile() {
        JsonArray expected = new JsonArray();expected.add(StorageRecoveryObservation.nfsClient("*", true, new JsonObject(), new JsonObject()));
        JsonArray actual = new JsonArray();actual.add(StorageRecoveryObservation.nfsClient("*", true, object("{'rootSquash':false}"), new JsonObject()));
        JsonObject observed = new JsonObject();observed.add("clients", actual);
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageRecoveryObservation.requireNfsClients(expected, observed));
    }
    @Test public void namespaceMustBeEnabledAndHaveObservedBackingSize() {
        JsonObject desired = object("{'namespaceSizeBytes':4096}");
        for (String json : new String[] {"{}", "{'configfsPresent':true,'enabled':false,'mappingStatus':'EXACT','runtimeVolumeUuid':'v','actualSizeBytes':4096}",
                "{'configfsPresent':true,'enabled':true,'mappingStatus':'EXACT','runtimeVolumeUuid':'other','actualSizeBytes':4096}",
                "{'configfsPresent':true,'enabled':true,'mappingStatus':'EXACT','runtimeVolumeUuid':'v','actualSizeBytes':2048}"}) {
            Assert.assertThrows(CloudRuntimeException.class, () -> StorageRecoveryObservation.requireNamespace(desired, object(json), "v"));
        }
        StorageRecoveryObservation.requireNamespace(desired, object("{'configfsPresent':true,'enabled':true,'mappingStatus':'EXACT','runtimeVolumeUuid':'v','actualSizeBytes':4096}"), "v");
    }
    @Test public void hostAuthenticationMustBeObservedWithoutExposingKeys() {
        JsonObject desired = object("{'dhChapEnabled':true,'dhChapCtrlEnabled':true}");
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageRecoveryObservation.requireNvmeHost(desired, null));
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageRecoveryObservation.requireNvmeHost(desired, object("{'dhChapConfigured':true}")));
        StorageRecoveryObservation.requireNvmeHost(desired, object("{'dhChapConfigured':true,'dhChapCtrlConfigured':true}"));
    }
    @Test public void parsedRuntimeNumbersMatchTypedDesiredUidAndGidWithoutHashBasedFalseDrift() {
        JsonArray expected = new JsonArray();expected.add(StorageRecoveryObservation.nfsClient("10.1.1.9/32", true, new JsonObject(), new JsonObject()));
        JsonObject observed = object("{'clients':[{'clients':'10.1.1.9/32','access':'RW','squash':'Root_Squash','anonUid':65534,'anonGid':65534}]}");
        StorageRecoveryObservation.requireNfsClients(expected, observed);
        observed.getAsJsonArray("clients").get(0).getAsJsonObject().addProperty("anonUid", 1002);
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageRecoveryObservation.requireNfsClients(expected, observed));
    }

}

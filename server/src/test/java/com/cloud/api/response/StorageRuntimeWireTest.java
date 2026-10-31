// Licensed under the Apache License, Version 2.0. See NOTICE.
// http://www.apache.org/licenses/LICENSE-2.0
package com.cloud.api.response;

import org.apache.cloudstack.api.response.StorageServiceRuntimeCapabilityResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeUpgradeResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeBundleResponse;
import org.junit.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static org.junit.Assert.*;

public class StorageRuntimeWireTest {
    @Test public void namedObjectsPreserveNativeIntegrityAndUnknownState() throws Exception {
        StorageServiceRuntimeCapabilityResponse cap=new StorageServiceRuntimeCapabilityResponse();
        cap.setInstanceId("instance");cap.setAvailable(true);cap.setCurrentVersion("baseline-known");
        cap.setPreviousVersion("bootstrap-known");cap.setUpdaterVersion(2);cap.setHostTransportVersion(2);
        cap.setRuntimeAbiVersion("1");cap.setDesiredStateSchemaVersion("1");
        cap.setIntegrityJson("{\"verified\":true,\"files\":[{\"path\":\"ablestack-storagectl\",\"sha256\":\"abc\"}]}");
        cap.setObjectName("storageserviceruntimecapability");
        cap.setResponseName("getstorageserviceruntimeupgradecapabilitiesresponse");
        String wire=ApiResponseSerializer.toSerializedString(cap,"json");
        JsonObject value=JsonParser.parseString(wire).getAsJsonObject()
            .getAsJsonObject("getstorageserviceruntimeupgradecapabilitiesresponse").getAsJsonObject("storageserviceruntimecapability");
        assertEquals(2,value.get("updaterversion").getAsInt());
        assertTrue(JsonParser.parseString(value.get("integrityjson").getAsString()).getAsJsonObject().get("verified").getAsBoolean());
        StorageServiceRuntimeUpgradeResponse upgrade=new StorageServiceRuntimeUpgradeResponse();
        upgrade.setId("operation");upgrade.setState("OBSERVATION_PENDING");upgrade.setPhase("OBSERVATION_PENDING");
        upgrade.setTransactionId("same-request");upgrade.setObjectName("storageserviceruntimeupgrade");
        upgrade.setResponseName("upgradestorageserviceruntimeresponse");
        String operation=ApiResponseSerializer.toSerializedString(upgrade,"json");
        assertTrue(operation.contains("OBSERVATION_PENDING"));
        StorageServiceRuntimeBundleResponse bundle=new StorageServiceRuntimeBundleResponse();
        bundle.setId("registered-bundle");bundle.setVersion("v2");bundle.setSigningKeyId("operator-key");
        bundle.setObjectName("storageserviceruntimebundle");bundle.setResponseName("registerstorageserviceruntimebundleresponse");
        String registration=ApiResponseSerializer.toSerializedString(bundle,"json");
        assertEquals("registered-bundle",JsonParser.parseString(registration).getAsJsonObject().getAsJsonObject("registerstorageserviceruntimebundleresponse").getAsJsonObject("storageserviceruntimebundle").get("id").getAsString());
        String output=System.getProperty("runtime.wire.fixture");
        if(output!=null) {
            java.nio.file.Path path=java.nio.file.Paths.get(output);
            assertTrue(path.toAbsolutePath().startsWith("/root/work/epic1438-preparation/"));
            JsonObject result=new JsonObject();
            result.add("capability",JsonParser.parseString(wire));
            result.add("operation",JsonParser.parseString(operation));
            result.add("bundleRegistration",JsonParser.parseString(registration));
            java.nio.file.Files.writeString(path,result.toString()+"\n");
        }
    }
}

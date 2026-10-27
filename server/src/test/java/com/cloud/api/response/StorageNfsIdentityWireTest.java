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
package com.cloud.api.response;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import org.apache.cloudstack.api.response.StorageNfsIdentityMappingResponse;
import org.junit.Test;
import static org.junit.Assert.*;
public class StorageNfsIdentityWireTest {
    @Test public void actualSerializerProducesNamedObservationAndInheritedJobId() throws Exception {
        StorageNfsIdentityMappingResponse response=new StorageNfsIdentityMappingResponse();
        response.setResponseName("getstoragenfsidentitymappingresponse");
        response.setInstanceid("instance");response.setMode("NUMERIC");response.setPolicyrevision(13L);
        response.setEffectivemode("NUMERIC");response.setObservationstatus("APPLIED");response.setObserveddomain("localdomain");
        response.setSecurityflavors(JsonParser.parseString("[\"sys\"]").getAsJsonArray());
        response.setEndpoints(JsonParser.parseString("[{\"endpoint\":\"0.0.0.0_2049\",\"port\":2049,\"configuredmode\":\"NUMERIC\",\"configuredrevision\":13,\"effectivemode\":\"NUMERIC\",\"observedrevision\":13,\"state\":\"APPLIED\"}]").getAsJsonArray());
        String serialized=ApiResponseSerializer.toSerializedString(response,"json");
        JsonObject body=JsonParser.parseString(serialized).getAsJsonObject().getAsJsonObject("getstoragenfsidentitymappingresponse").getAsJsonObject("nfsidentitymapping");
        assertEquals("APPLIED",body.get("observationstatus").getAsString());
        assertEquals(13,body.getAsJsonArray("endpoints").get(0).getAsJsonObject().get("observedrevision").getAsInt());
        String output=System.getProperty("nfs.identity.wire.fixture");
        if(output!=null) java.nio.file.Files.writeString(java.nio.file.Paths.get(output),serialized);
        response.setJobId("job-id"); response.setResponseName("updatestoragenfsidentitymappingresponse");
        String async=ApiResponseSerializer.toSerializedString(response,"json");
        assertEquals(1,async.split("\"jobid\"", -1).length-1);
    }
}

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
import com.google.gson.*;
import org.apache.cloudstack.api.response.SharedFSDeletionResponse;
import org.junit.Test;
import static org.junit.Assert.*;
public class SharedFSDeletionWireTest {
    @Test public void actualResponseSerializerPreservesNestedPlanAndFailure() throws Exception {
        JsonObject sample=JsonParser.parseString("{\"success\":false,\"operationid\":\"11111111-1461-4000-8000-000000000001\",\"requestid\":\"11111111-1461-4000-8000-000000000002\",\"state\":\"RECOVERY_REQUIRED\",\"phase\":\"DETACHING_DATA\",\"canresume\":true,\"originalfailure\":{\"code\":\"DATA_DETACH_UNCONFIRMED\",\"phase\":\"DETACHING_DATA\"},\"plan\":{\"fingerprint\":\"fixture\",\"policy\":\"PRESERVE\",\"volumes\":[{\"uuid\":\"11111111-1461-4000-8000-000000000003\",\"name\":\"기본 DATA\",\"type\":\"DATADISK\",\"size\":\"10737418240\",\"deletable\":true}]},\"progress\":{\"volumes\":{\"11111111-1461-4000-8000-000000000003\":\"DETACHED_PRESERVED\"}}}").getAsJsonObject();
        SharedFSDeletionResponse response=new SharedFSDeletionResponse(sample);response.setResponseName("getsharedfilesystemdeletionoperationresponse");
        String json=ApiResponseSerializer.toSerializedString(response,"json");JsonObject root=JsonParser.parseString(json).getAsJsonObject();assertTrue(json.contains("DATA_DETACH_UNCONFIRMED"));assertTrue(json.contains("DETACHED_PRESERVED"));
        org.apache.cloudstack.api.response.ListResponse<SharedFSDeletionResponse> list=new org.apache.cloudstack.api.response.ListResponse<>();
        SharedFSDeletionResponse item=new SharedFSDeletionResponse(sample);item.setObjectName("deletionoperation");list.setResponses(java.util.List.of(item));list.setResponseName("listsharedfilesystemdeletionoperationsresponse");
        String listJson=ApiResponseSerializer.toSerializedString(list,"json");
        assertTrue(JsonParser.parseString(listJson).getAsJsonObject().getAsJsonObject("listsharedfilesystemdeletionoperationsresponse").get("deletionoperation").isJsonArray());
        String file=System.getProperty("sharedfs.deletion.wire.fixture");if(file!=null){assertTrue(file.startsWith("/root/work/"));java.nio.file.Files.writeString(java.nio.file.Path.of(file),json+"\n");java.nio.file.Files.writeString(java.nio.file.Path.of(file+".list.json"),listJson+"\n");}
    }
}

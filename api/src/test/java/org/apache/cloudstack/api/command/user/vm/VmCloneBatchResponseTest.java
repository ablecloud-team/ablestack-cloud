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
package org.apache.cloudstack.api.command.user.vm;

import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.VmCloneBatchResponse;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import static org.junit.Assert.*;

public class VmCloneBatchResponseTest {
    @Test public void legacyVmFieldsRemainAlongsideCompleteBatch() {
        UserVmResponse vm=new UserVmResponse();ReflectionTestUtils.setField(vm,"id","vm-2");
        VmCloneBatchResponse batch=new VmCloneBatchResponse();batch.requestId="request";batch.requestedCount=2;batch.status="SUCCEEDED";
        for(int i=1;i<=2;i++){VmCloneBatchResponse.Target t=new VmCloneBatchResponse.Target();t.index=i;t.name="copy-"+i;t.id="vm-"+i;t.created=true;batch.targets.add(t);}
        vm.setCloneBatch(batch);JsonObject json=new Gson().toJsonTree(vm).getAsJsonObject();
        assertEquals("vm-2",json.get("id").getAsString());assertEquals(2,json.getAsJsonObject("clonebatch").getAsJsonArray("targets").size());
    }
    @Test public void ordinaryVmResponsesDoNotAcquireBatchFields() {
        assertFalse(new Gson().toJsonTree(new UserVmResponse()).getAsJsonObject().has("clonebatch"));
    }
    @Test public void retryKeepsOriginalTargetIndices() {
        CloneVMCmd cmd=new CloneVMCmd();ReflectionTestUtils.setField(cmd,"count",2);
        assertEquals(List.of(1,2),cmd.getCloneIndices());cmd.setCloneIndices(List.of(2));assertEquals(List.of(2),cmd.getCloneIndices());
    }
    @Test public void notfoundViewHasNoResourceOrFailureIdentifiers() {
        VmCloneBatchResponse response=new VmCloneBatchResponse();response.requestId="provided";response.status="NOTFOUND";
        JsonObject json=new Gson().toJsonTree(response).getAsJsonObject();assertFalse(json.has("sourceid"));assertFalse(json.has("jobid"));assertFalse(json.has("error"));assertEquals(0,json.getAsJsonArray("targets").size());
    }
}

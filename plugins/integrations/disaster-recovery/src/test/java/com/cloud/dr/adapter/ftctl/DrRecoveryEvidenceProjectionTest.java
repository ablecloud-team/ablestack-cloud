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

package com.cloud.dr.adapter.ftctl;

import java.lang.reflect.Method;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;

public class DrRecoveryEvidenceProjectionTest {
    @Test
    public void compactionPreservesFalseReadbackAndOriginWithoutCredentials() throws Exception {
        Method method = FtctlDrRuntimeProjectionAdapter.class.getDeclaredMethod("compactRuntimeStatusJson", String.class);
        method.setAccessible(true);
        JsonObject input = new JsonObject();
        input.addProperty("reverse_readback_verified", false);
        input.addProperty("reverse_readback_verified_bytes", 0);
        input.addProperty("reverse_verification_method", "QEMU_BACKUP_COMPLETION");
        input.addProperty("reverse_origin_checkpoint_sequence", 182);
        input.addProperty("reverse_origin_checkpoint_ref", "ftctl:plan:producer:182");
        input.addProperty("credentials", "must-not-be-projected");
        JsonObject result = JsonParser.parseString((String) method.invoke(new FtctlDrRuntimeProjectionAdapter(), input.toString())).getAsJsonObject();
        Assert.assertFalse(result.get("reverse_readback_verified").getAsBoolean());
        Assert.assertEquals(0, result.get("reverse_readback_verified_bytes").getAsLong());
        Assert.assertEquals(182, result.get("reverse_origin_checkpoint_sequence").getAsLong());
        Assert.assertEquals("ftctl:plan:producer:182", result.get("reverse_origin_checkpoint_ref").getAsString());
        Assert.assertEquals("QEMU_BACKUP_COMPLETION", result.get("reverse_verification_method").getAsString());
        Assert.assertFalse(result.has("credentials"));
    }
    @Test
    public void compactionKeepsTransferIdentity() throws Exception {
        Method method = FtctlDrRuntimeProjectionAdapter.class.getDeclaredMethod("compactRuntimeStatusJson", String.class);
        method.setAccessible(true);
        JsonObject input = new JsonObject();
        input.addProperty("transfer_plan_uuid", "plan");
        input.addProperty("transfer_run_uuid", "parent-run");
        input.addProperty("transfer_direction", "KVM_TO_VMWARE");
        JsonObject result = JsonParser.parseString((String) method.invoke(new FtctlDrRuntimeProjectionAdapter(), input.toString())).getAsJsonObject();
        for (String key : new String[] {"transfer_plan_uuid", "transfer_run_uuid", "transfer_direction"}) {
            Assert.assertEquals(input.get(key), result.get(key));
        }
    }

}

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
import java.util.Map;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
public class StorageRootRollbackCompatibilityTest {
    private JsonObject oldKernel(){JsonObject actual=new JsonObject();actual.addProperty("kernel","6.1-old");actual.addProperty("dhChapSupported",false);actual.addProperty("dhChapCtrlSupported",false);return actual;}
    @Test public void noAuthOldRootCanBeRetainedUntilCurrentDesiredStateRequiresNewKernelAuth(){
        Assert.assertTrue(StorageRootRollbackCompatibility.evaluate(false,false,oldKernel(),Map.of()).get("compatible").getAsBoolean());
        JsonObject blocked=StorageRootRollbackCompatibility.evaluate(true,false,oldKernel(),Map.of());Assert.assertFalse(blocked.get("compatible").getAsBoolean());Assert.assertTrue(blocked.toString().contains("PREVIOUS_KERNEL_NVME_DHCHAP_UNSUPPORTED"));
    }
    @Test public void savedActualKernelCapsProtectLegacyTemplatesWithoutMetadata(){
        JsonObject actual=oldKernel();actual.addProperty("dhChapSupported",true);actual.addProperty("dhChapCtrlSupported",true);Assert.assertTrue(StorageRootRollbackCompatibility.evaluate(true,true,actual,Map.of()).get("compatible").getAsBoolean());
    }
    @Test public void metadataCannotOverrideAnObservedUnsupportedKernelAndUnknownAuthCannotBeAssumed(){
        Map<String,String> declared=Map.of("storage.service.nvme.target.auth","true","storage.service.runtime.abi","1","storage.service.template.version","new-kernel");
        Assert.assertFalse(StorageRootRollbackCompatibility.evaluate(true,false,oldKernel(),declared).get("compatible").getAsBoolean());Assert.assertFalse(StorageRootRollbackCompatibility.evaluate(true,true,null,Map.of()).get("compatible").getAsBoolean());Assert.assertTrue(StorageRootRollbackCompatibility.evaluate(true,true,null,declared).get("compatible").getAsBoolean());
    }
}

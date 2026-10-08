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

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;

public class StorageVolumeFormatCapabilityTest {
    private static class Manager extends StorageServiceManagerImpl {
        JsonObject capability=new JsonObject();int calls;
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout) {
            Assert.assertEquals("volume operation capabilities",command);Assert.assertTrue(timeout<=5);calls++;return capability;
        }
    }
    @Test public void oldRuntimeCannotStartDefaultFormattingWhenItIgnoresSkipDiscard() {
        Manager manager=new Manager();manager.capability.addProperty("success",true);
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.requireNewVolumeFormatSupport(null));
        Assert.assertEquals(1,manager.calls);
        JsonArray policies=new JsonArray();policies.add("DEFAULT");manager.capability.add("formatDiscardPolicies",policies);
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.requireNewVolumeFormatSupport(null));
    }
    @Test public void actualSkipSparseAndSuccessReceiptSupportMustAllBeAttested() {
        Manager manager=new Manager();JsonArray policies=new JsonArray();policies.add("SKIP_DISCARD");manager.capability.add("formatDiscardPolicies",policies);manager.capability.addProperty("sparseFormatRequired",true);
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.requireNewVolumeFormatSupport(null));manager.capability.addProperty("formatterSuccessReceiptSupported",true);manager.requireNewVolumeFormatSupport(null);
    }
    @Test public void missingOrWrongPolicyEchoCannotClaimFormattedDataReady() {
        Manager manager=new Manager();JsonObject result=new JsonObject();Assert.assertThrows(CloudRuntimeException.class,()->manager.requireNewVolumeFormatEcho(result));
        result.addProperty("formatInvoked",false);manager.requireNewVolumeFormatEcho(result);
        result.addProperty("formatInvoked",true);JsonObject operation=new JsonObject();operation.addProperty("formatDiscardPolicy","DEFAULT");operation.add("formatterSuccessReceipt",new JsonObject());result.add("operation",operation);
        Assert.assertThrows(CloudRuntimeException.class,()->manager.requireNewVolumeFormatEcho(result));operation.addProperty("formatDiscardPolicy","SKIP_DISCARD");manager.requireNewVolumeFormatEcho(result);
    }
}

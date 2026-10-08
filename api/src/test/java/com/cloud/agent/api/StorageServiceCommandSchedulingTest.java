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

package com.cloud.agent.api;
import org.junit.*;
public class StorageServiceCommandSchedulingTest {
    @Test public void exactReadOnlyQueriesCanRunWhileAnotherVmOnTheHostIsFormatting(){
        for(String command:new String[]{"health","inventory","sessions","operation observe","operation verify","operation resources","operation generation status","operation generation frozen","volume operation status","operation maintenance status","operation root-data inspect","identity capsule capabilities","nfs idmapping preflight","operation writer-idle","operation reservation status","smb identity inspect"}) Assert.assertFalse(command,new StorageServiceHostCommand("same-vm",command,"{}",30).executeInSequence());
    }
    @Test public void writersAndUnknownOrExtendedCommandStringsAlwaysRemainSerialized(){
        for(String command:new String[]{"volume attach inspect","nfs export apply","smb share apply","iscsi target apply","nvmeof subsystem apply","operation generation begin","operation generation commit","operation quiesce","operation maintenance release","operation root-data inspect extra","health apply","HEALTH","operation generation status-extra"}) Assert.assertTrue(command,new StorageServiceHostCommand("same-vm",command,"{}",300).executeInSequence());
        Assert.assertTrue(new StorageServiceHostCommand("same-vm",null,"{}",30).executeInSequence());
    }
    @Test public void runtimeMetadataAndInstalledReadbackAreReadOnlyWhileTransfersAndActivationStaySerialized(){
        for(StorageServiceRuntimeOperation operation:StorageServiceRuntimeOperation.values()) {
            boolean read=operation==StorageServiceRuntimeOperation.CAPABILITIES || operation==StorageServiceRuntimeOperation.STATUS || operation==StorageServiceRuntimeOperation.READBACK;
            Assert.assertEquals(operation.name(),!read,new StorageServiceRuntimeHostCommand("same-vm",operation,"scope",null,30).executeInSequence());
        }
    }
    @Test public void onlyExactScopedLeaseRenewalBypassesTheLongWriterQueue() {
        Assert.assertFalse(new StorageServiceHostCommand("same-vm","operation reservation renew","{}",15).executeInSequence());
        for(String value:new String[]{"operation reservation acquire","operation reservation release","smb identity rebind","smb identity inspect extra","operation reservation renew extra","operation reservation renew-all","operation reservation"}) {
            Assert.assertTrue(value,new StorageServiceHostCommand("same-vm",value,"{}",15).executeInSequence());
        }
    }

}

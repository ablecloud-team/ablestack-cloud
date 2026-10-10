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

package com.cloud.hypervisor.kvm.resource.wrapper;
import java.util.Collections;
import com.cloud.agent.api.StorageServiceHostCommand;
import org.junit.Assert;
import org.junit.Test;
public class StorageServiceHostDiagnosticTest {
    private final LibvirtStorageServiceHostCommandWrapper wrapper=new LibvirtStorageServiceHostCommandWrapper();
    @Test public void structuredGuestErrorIsUsedWhenStderrIsAbsent() {
        StorageServiceHostCommand command=new StorageServiceHostCommand("fixture","nfs export apply","{}",30);
        String result=wrapper.commandFailureDetails(command,1,"{\"success\":false,\"errorCode\":\"APPLY_FAILED\",\"message\":\"fixture probe failed\"}",null);
        Assert.assertTrue(result.contains("APPLY_FAILED"));Assert.assertTrue(result.contains("fixture probe failed"));Assert.assertFalse(result.endsWith(": null"));
    }
    @Test public void secretBearingFailuresDoNotReachAuditDiagnostics() {
        StorageServiceHostCommand command=new StorageServiceHostCommand("fixture","smb share apply","{}",30,Collections.singleton("password"));
        Assert.assertFalse(wrapper.commandFailureDetails(command,1,"fixture-only-secret","fixture-only-secret").contains("fixture-only-secret"));
        Assert.assertFalse(wrapper.commandExceptionDetails(command,"fixture-only-secret").contains("fixture-only-secret"));
    }
}

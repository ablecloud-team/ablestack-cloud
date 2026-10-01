//
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
//

package com.cloud.hypervisor.kvm.resource.wrapper;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import com.google.gson.JsonParser;
public class LibvirtCheckVmBackupTrackingCommandWrapperTest {
    @Test public void detectsLiveAndOfflineBitmaps() {
        assertTrue(LibvirtCheckVmBackupTrackingCommandWrapper.hasBitmaps(new JsonParser().parse("{\"return\":[{\"inserted\":{\"dirty-bitmaps\":[{\"name\":\"backup\"}]}}]}")));
        assertTrue(LibvirtCheckVmBackupTrackingCommandWrapper.hasBitmaps(new JsonParser().parse("[{\"format-specific\":{\"data\":{\"bitmaps\":[{\"name\":\"backup\"}]}}}]")));
    }
    @Test public void acceptsEmptyTrackingLists() {
        assertFalse(LibvirtCheckVmBackupTrackingCommandWrapper.hasBitmaps(new JsonParser().parse("{\"return\":[{\"inserted\":{\"dirty-bitmaps\":[]}}]}")));
        assertFalse(LibvirtCheckVmBackupTrackingCommandWrapper.hasBitmaps(new JsonParser().parse("[{\"format\":\"qcow2\"}]")));
    }
}

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

package com.cloud.hypervisor.kvm.resource;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import org.mockito.MockedStatic;
import org.libvirt.Domain;
import org.libvirt.DomainInfo;

public class KvmBoundedStatsTest {
    @Test public void parsesVirshCountersAndMissingNowaitFieldsFailClosed() throws Exception {
        Domain domain = mock(Domain.class);
        when(domain.getUUIDString()).thenReturn("726c3e62-a867-4d4f-ae8b-ff742db97cef");
        try (MockedStatic<KvmVmOperationGuard> guard = mockStatic(KvmVmOperationGuard.class)) {
            guard.when(() -> KvmVmOperationGuard.probe(anyLong(), any(String[].class)))
                    .thenReturn("state.state=1\ncpu.time=123\nvcpu.current=4\nballoon.current=1024\nballoon.maximum=2048\n");
            DomainInfo info = KvmBoundedStats.info(domain);
            assertEquals(123, info.cpuTime);
            assertEquals(4, info.nrVirtCpu);
            assertEquals(2048, info.maxMem);
            assertEquals(DomainInfo.DomainState.VIR_DOMAIN_RUNNING, info.state);
            KvmBoundedStats.clear();
            guard.when(() -> KvmVmOperationGuard.probe(anyLong(), any(String[].class))).thenReturn("state.state=1\n");
            try { KvmBoundedStats.info(domain); fail("partial NOWAIT sample accepted"); }
            catch (RuntimeException expected) { assertTrue(expected.getMessage().contains("Unavailable NOWAIT field")); }
        } finally { KvmBoundedStats.clear(); }
        assertEquals("123", KvmBoundedStats.parse("sda rd_bytes 123\n").get("rd_bytes"));
    }
}

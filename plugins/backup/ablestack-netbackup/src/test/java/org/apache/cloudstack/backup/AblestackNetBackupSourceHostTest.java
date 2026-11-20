// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.exception.CloudRuntimeException;

public class AblestackNetBackupSourceHostTest {
    private final AblestackNetBackupProvider provider = new AblestackNetBackupProvider();
    private final HostDao hosts = mock(HostDao.class);
    private final HostVO source = mock(HostVO.class);
    private final BackupVO backup = new BackupVO();

    @Before public void setup() {
        ReflectionTestUtils.setField(provider, "hostDao", hosts);
        backup.setZoneId(1L);
        backup.setHostId(22L);
        backup.setDetails(new HashMap<>());
        backup.getDetails().put("netbackup.policy.name", "PoC.GFS_02+full");
        when(hosts.findById(22L)).thenReturn(source);
        when(source.getName()).thenReturn("mold-host-2");
        when(source.getStatus()).thenReturn(Status.Up);
        when(source.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(source.getDataCenterId()).thenReturn(1L);
    }

    @Test public void customPolicyUsesRecordedHostForRestoreAndCleanupAfterVmMigration() {
        assertSame(source, ReflectionTestUtils.invokeMethod(provider, "resolveBackupSourceHostForRestore", backup));
        assertSame(source, ReflectionTestUtils.invokeMethod(provider, "resolveBackupCleanupHost", backup));
        assertEquals("mold-host-2", ReflectionTestUtils.invokeMethod(provider, "getRestoreSourceHost", backup));
        verify(hosts, never()).findByName("PoC.GFS_02+full");
    }

    @Test public void oldEqualPolicyAndHostNamesContinueToUseTheRecordedHost() {
        backup.getDetails().put("netbackup.policy.name", "mold-host-2");
        assertSame(source, ReflectionTestUtils.invokeMethod(provider, "resolveBackupSourceHostForRestore", backup));
        assertSame(source, ReflectionTestUtils.invokeMethod(provider, "resolveBackupCleanupHost", backup));
    }

    @Test public void missingRecordedHostCannotRedirectToAnotherHostNamedAfterThePolicy() {
        when(hosts.findById(22L)).thenReturn(null);
        assertNull(ReflectionTestUtils.invokeMethod(provider, "resolveBackupCleanupHost", backup));
        assertThrows(CloudRuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(provider, "resolveBackupSourceHostForRestore", backup));
        verify(hosts, never()).findByName("PoC.GFS_02+full");
    }

    @Test public void unavailableOrWrongZoneHostCannotBeUsedForRestoreOrCleanup() {
        when(source.getStatus()).thenReturn(Status.Disconnected);
        assertNull(ReflectionTestUtils.invokeMethod(provider, "resolveBackupCleanupHost", backup));
        assertThrows(CloudRuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(provider, "resolveBackupSourceHostForRestore", backup));
        when(source.getStatus()).thenReturn(Status.Up);
        when(source.getDataCenterId()).thenReturn(2L);
        assertNull(ReflectionTestUtils.invokeMethod(provider, "resolveBackupCleanupHost", backup));
        assertThrows(CloudRuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(provider, "resolveBackupSourceHostForRestore", backup));
    }

    @Test public void explicitSourceDetailSupportsRecordsWithoutHostId() {
        backup.setHostId(null);
        backup.getDetails().put("netbackup.source.host.name", "mold-host-2");
        when(hosts.findByName("mold-host-2")).thenReturn(source);
        assertSame(source, ReflectionTestUtils.invokeMethod(provider, "resolveBackupSourceHostForRestore", backup));
        verify(hosts, never()).findByName("PoC.GFS_02+full");
    }

    @Test public void legacyRecordsWithoutSourceIdentityRetainEqualNameCompatibility() {
        backup.setHostId(null);
        backup.getDetails().put("netbackup.policy.name", "mold-host-2");
        when(hosts.findByName("mold-host-2")).thenReturn(source);
        assertSame(source, ReflectionTestUtils.invokeMethod(provider, "resolveBackupSourceHostForRestore", backup));
    }
}

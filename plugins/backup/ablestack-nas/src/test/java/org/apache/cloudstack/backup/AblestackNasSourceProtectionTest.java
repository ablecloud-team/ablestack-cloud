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

import java.util.Map;
import com.cloud.agent.AgentManager;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class AblestackNasSourceProtectionTest {
    private final AblestackNasBackupProvider provider = new AblestackNasBackupProvider();
    private final ThirdPartyBackupVolumeService sources = mock(ThirdPartyBackupVolumeService.class);
    private final AgentManager agents = mock(AgentManager.class);
    private final BackupVO backup = mock(BackupVO.class);

    @Before public void setup() {
        ReflectionTestUtils.setField(provider, "thirdPartyBackupVolumeService", sources);
        ReflectionTestUtils.setField(provider, "agentManager", agents);
        var start = new ThirdPartyBackupStart.Operation();
        start.plan = new ThirdPartyBackupStart.Plan();
        start.plan.nasSource = new ThirdPartyBackupStart.NasSource();
        when(backup.getDetails()).thenReturn(Map.of(ThirdPartyBackupStart.DETAIL_KEY, new Gson().toJson(start)));
        when(backup.getHostId()).thenReturn(4L);
        when(backup.getUuid()).thenReturn("backup-1");
        HostDao hosts = mock(HostDao.class);
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(4L);
        when(hosts.findById(4L)).thenReturn(host);
        ReflectionTestUtils.setField(provider, "hostDao", hosts);
    }

    @Test public void failedSourceCleanupPreventsRepositoryDeletion() {
        when(sources.cleanupNasBackupSource(backup)).thenReturn(false);
        assertThrows(CloudRuntimeException.class, () -> ReflectionTestUtils.invokeMethod(provider, "deleteOwnedBackup", backup, true));
        verify(sources).cleanupNasBackupSource(backup);
        verifyNoInteractions(agents);
    }

    @Test public void anUnstartedUnknownJobCanBeCanceledByItsPermanentFence() throws Exception {
        BackupAnswer status = mock(BackupAnswer.class);
        when(status.getResult()).thenReturn(true);
        when(status.getState()).thenReturn("UNKNOWN");
        when(agents.send(eq(4L), any(AblestackBackupJobStatusCommand.class))).thenReturn(status);
        when(sources.cleanupNasBackupSource(backup)).thenReturn(true);
        assertTrue(provider.cancelBackup(mock(VirtualMachine.class), backup));
        verify(sources).cleanupNasBackupSource(backup);
        verify(agents).send(eq(4L), any(AblestackBackupJobStatusCommand.class));
    }
}

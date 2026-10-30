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

import java.util.List;

import org.apache.cloudstack.backup.dao.BackupRepositoryDao;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.host.HostVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.utils.exception.CloudRuntimeException;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class AblestackNasRepositoryCapacityTest {
    private static final long GIB = 1024L * 1024 * 1024;
    private final AblestackNasBackupProvider provider = new AblestackNasBackupProvider();
    private final AgentManager agent = mock(AgentManager.class);
    private final HostVO host = mock(HostVO.class);
    private final BackupRepository repository = mock(BackupRepository.class);
    private final VolumeVO volume = mock(VolumeVO.class);
    private final ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
    private ConfigDepotImpl originalDepot;
    private long availableBytes;

    @Before
    public void setup() throws Exception {
        originalDepot = (ConfigDepotImpl) ReflectionTestUtils.getField(BackupManager.NasBackupRepositoryCapacityBufferPercent, "s_depot");
        ConfigKey.init(depot);
        ReflectionTestUtils.setField(provider, "agentManager", agent);
        ReflectionTestUtils.setField(provider, "backupRepositoryDao", mock(BackupRepositoryDao.class));
        when(host.getId()).thenReturn(7L);
        when(host.getName()).thenReturn("backup-host");
        when(repository.getName()).thenReturn("nas-repository");
        when(volume.getSize()).thenReturn(300 * GIB);
        when(volume.getState()).thenReturn(Volume.State.Ready);
        when(agent.send(eq(7L), any(GetBackupStorageStatsCommand.class))).thenAnswer(call -> {
            BackupStorageStatsAnswer answer = new BackupStorageStatsAnswer(call.getArgument(1), true, null);
            answer.setTotalSize(2000 * GIB);
            answer.setUsedSize(2000 * GIB - availableBytes);
            return answer;
        });
    }

    @After
    public void restoreDepot() {
        ConfigKey.init(originalDepot);
    }

    private void percentage(String value) {
        when(depot.getConfigStringValue(BackupManager.NasBackupRepositoryCapacityBufferPercent.key(), ConfigKey.Scope.Global, null))
                .thenReturn(value);
    }

    private void check(String type, long available) {
        availableBytes = available;
        ReflectionTestUtils.invokeMethod(provider, "validateBackupRepositoryCapacity", host, repository, List.of(volume),
                "i-2-test-VM", type, "QCOW2");
    }

    private void assertMinimumAvailable(String type, long minimum) {
        check(type, minimum);
        CloudRuntimeException failure = assertThrows(CloudRuntimeException.class, () -> check(type, minimum - 1));
        assertTrue(failure.getMessage().contains("Required at least [" + minimum + "]"));
    }

    @Test
    public void defaultPercentageKeepsTheExistingFullBackupThreshold() {
        assertMinimumAvailable("FULL", 360 * GIB);
    }

    @Test
    public void configuredPercentageChangesTheFullBackupThreshold() {
        percentage("15");
        assertMinimumAvailable("FULL", 345 * GIB);
    }

    @Test
    public void zeroPercentStillRequiresTenGiBOfHeadroom() {
        percentage("0");
        assertMinimumAvailable("FULL", 310 * GIB);
    }

    @Test
    public void maximumSupportedPercentageIsApplied() {
        percentage("100");
        assertMinimumAvailable("FULL", 600 * GIB);
    }

    @Test
    public void incrementalEstimateRetainsTheMinimumBuffer() {
        assertMinimumAvailable("INCREMENTAL", 40 * GIB);
    }

    @Test
    public void configuredPercentageAlsoAppliesToIncrementalBackups() {
        percentage("100");
        assertMinimumAvailable("INCREMENTAL", 60 * GIB);
    }

    @Test
    public void nextCapacityCheckReadsTheChangedSettingWithoutRestart() {
        percentage("20");
        assertThrows(CloudRuntimeException.class, () -> check("FULL", 345 * GIB));
        percentage("15");
        check("FULL", 345 * GIB);
    }

    @Test
    public void restorePrimaryStoragePercentageDoesNotChangeBackupAdmission() {
        when(depot.getConfigStringValue(BackupManager.RestorePrimaryStorageCapacityBufferPercent.key(), ConfigKey.Scope.Global, null))
                .thenReturn("0");
        percentage("20");
        assertMinimumAvailable("FULL", 360 * GIB);
        verify(depot, never()).getConfigStringValue(BackupManager.RestorePrimaryStorageCapacityBufferPercent.key(), ConfigKey.Scope.Global, null);
    }

    @Test
    public void invalidPercentageCannotBypassTheCapacityCheck() {
        for (String value : List.of("-1", "101")) {
            percentage(value);
            assertThrows(CloudRuntimeException.class, () -> check("FULL", 1000 * GIB));
        }
        verifyNoInteractions(agent);
    }
}

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

package org.apache.cloudstack.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceState;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;

public class ThirdPartyBackupStagingServiceImplTest {
    @InjectMocks
    private ThirdPartyBackupStagingServiceImpl service;
    @Mock
    private ConfigurationDao configurationDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private AgentManager agentManager;
    @Mock
    private Supplier<String> saveConfiguration;

    private AutoCloseable mocks;
    private MockedStatic<GlobalLock> lockFactory;
    private ConfigDepotImpl originalDepot;
    private ConfigDepotImpl depot;
    private final Map<String, String> settings = new HashMap<>();

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(10)).thenReturn(true);
        lockFactory = mockStatic(GlobalLock.class);
        lockFactory.when(() -> GlobalLock.getInternLock("backup.thirdparty.staging.configuration")).thenReturn(lock);
        originalDepot = (ConfigDepotImpl) ReflectionTestUtils.getField(BackupManager.BackupFrameworkEnabled, "s_depot");
        depot = mock(ConfigDepotImpl.class);
        ConfigKey.init(depot);
        when(depot.getConfigStringValue(eq(BackupManager.BackupFrameworkEnabled.key()), eq(ConfigKey.Scope.Zone), anyLong()))
                .thenReturn("true");
        when(depot.getConfigStringValue(eq(BackupManager.BackupProviderPlugin.key()), eq(ConfigKey.Scope.Zone), anyLong()))
                .thenReturn("ablestack-commvault,ablestack-netbackup,ablestack-veeam");
        settings.put(BackupManager.ThirdPartyStagingEnable.key(), "false");
        settings.put(BackupManager.ThirdPartyStagingStorageType.key(), "GFS2");
        settings.put(BackupManager.ThirdPartyStagingMountPath.key(), "/mnt/staging");
        settings.put(BackupManager.ThirdPartyStagingRootPath.key(), "/mnt/staging/jobs");
        when(configurationDao.getValue(any(String.class))).thenAnswer(invocation -> settings.get(invocation.getArgument(0)));
    }

    @After
    public void tearDown() throws Exception {
        ConfigKey.init(originalDepot);
        lockFactory.close();
        mocks.close();
    }

    @Test
    public void activationSavesOnlyAfterEveryHostAcceptsLargestVolumePlusBuffer() throws Exception {
        HostVO first = host(1L, "cube1");
        HostVO second = host(2L, "cube2");
        when(hostDao.listByType(Host.Type.Routing)).thenReturn(List.of(first, second));
        when(volumeDao.findLargestUserVmVolumeSize()).thenReturn(100L);
        when(agentManager.send(anyLong(), any(AblestackThirdPartyStagingCommand.class)))
                .thenReturn(new Answer(null, true, "120"));
        when(saveConfiguration.get()).thenAnswer(invocation -> {
            verify(agentManager).send(eq(1L), any(AblestackThirdPartyStagingCommand.class));
            verify(agentManager).send(eq(2L), any(AblestackThirdPartyStagingCommand.class));
            return "true";
        });

        assertEquals("true", service.updateConfiguration(BackupManager.ThirdPartyStagingEnable.key(), "true", saveConfiguration));
        ArgumentCaptor<AblestackThirdPartyStagingCommand> commands = ArgumentCaptor.forClass(AblestackThirdPartyStagingCommand.class);
        verify(agentManager).send(eq(1L), commands.capture());
        assertEquals(120L, commands.getValue().getRequiredBytes());
        assertEquals("/mnt/staging/jobs", commands.getValue().getPath());
    }

    @Test
    public void failedHostAndInsufficientSpacePreventActivationFromBeingSaved() throws Exception {
        when(hostDao.listByType(Host.Type.Routing)).thenReturn(List.of(host(1L, "cube1"), host(2L, "cube2")));
        when(volumeDao.findLargestUserVmVolumeSize()).thenReturn(100L);
        when(agentManager.send(eq(1L), any(AblestackThirdPartyStagingCommand.class)))
                .thenReturn(new Answer(null, false, "Staging mount is not writable"));
        when(agentManager.send(eq(2L), any(AblestackThirdPartyStagingCommand.class)))
                .thenReturn(new Answer(null, true, "119"));

        InvalidParameterValueException failure = assertThrows(InvalidParameterValueException.class,
                () -> service.updateConfiguration(BackupManager.ThirdPartyStagingEnable.key(), "true", saveConfiguration));

        assertTrue(failure.getMessage().contains("cube1"));
        assertTrue(failure.getMessage().contains("cube2"));
        verify(saveConfiguration, never()).get();
        assertThrows(CloudRuntimeException.class, service::requireEnabled);
    }

    @Test
    public void hostsInNasOnlyZonesAreExcludedFromActivation() {
        when(hostDao.listByType(Host.Type.Routing)).thenReturn(List.of(host(1L, "cube1")));
        when(depot.getConfigStringValue(BackupManager.BackupProviderPlugin.key(), ConfigKey.Scope.Zone, 1L)).thenReturn("nas");

        assertThrows(InvalidParameterValueException.class,
                () -> service.updateConfiguration(BackupManager.ThirdPartyStagingEnable.key(), "true", saveConfiguration));
        verify(saveConfiguration, never()).get();
    }

    @Test
    public void enabledStagingSettingsCannotBeChangedWithoutRevalidation() {
        settings.put(BackupManager.ThirdPartyStagingEnable.key(), "true");

        assertThrows(InvalidParameterValueException.class,
                () -> service.updateConfiguration(BackupManager.ThirdPartyStagingRootPath.key(), "/mnt/other", saveConfiguration));
        verify(saveConfiguration, never()).get();
    }

    @Test
    public void runtimeRechecksHostEvenAfterSuccessfulActivation() throws Exception {
        settings.put(BackupManager.ThirdPartyStagingEnable.key(), "true");
        HostVO worker = host(1L, "cube1");
        when(agentManager.send(eq(1L), any(AblestackThirdPartyStagingCommand.class)))
                .thenReturn(new Answer(null, true, "100"), new Answer(null, false, "Unable to mount staging storage"));

        assertEquals(100L, service.getAvailableBytes(worker, "/mnt/staging/jobs/ablestack-commvault"));
        assertThrows(CloudRuntimeException.class,
                () -> service.getAvailableBytes(worker, "/mnt/staging/jobs/ablestack-commvault"));
    }

    @Test
    public void runtimeRejectsOldOrUnconfiguredPathsBeforeContactingHost() throws Exception {
        settings.put(BackupManager.ThirdPartyStagingEnable.key(), "true");

        assertThrows(CloudRuntimeException.class, () -> service.getAvailableBytes(host(1L, "cube1"), "/tmp/mold/backup"));
        verify(agentManager, never()).send(anyLong(), any(AblestackThirdPartyStagingCommand.class));
    }

    private HostVO host(long id, String name) {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(id);
        when(host.getName()).thenReturn(name);
        when(host.getDataCenterId()).thenReturn(1L);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);
        return host;
    }
}

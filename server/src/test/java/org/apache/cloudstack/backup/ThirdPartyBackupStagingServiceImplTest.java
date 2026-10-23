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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
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
import com.google.gson.Gson;

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
    @Mock
    private BackupDao backupDao;
    @Mock
    private BackupDetailsDao backupDetailsDao;

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
        List<HostVO> hosts = List.of(host(1L, "cube1"), host(2L, "cube2"));
        when(hostDao.listByType(Host.Type.Routing)).thenReturn(hosts);
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
        HostVO host = host(1L, "cube1");
        when(hostDao.listByType(Host.Type.Routing)).thenReturn(List.of(host));
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
    public void allStagingBackendsAreValidatedOnTheSelectedWorkerHost() throws Exception {
        settings.put(BackupManager.ThirdPartyStagingEnable.key(), "true");
        settings.put(BackupManager.ThirdPartyStagingNfsSource.key(), "nas:/staging");
        HostVO worker = host(2L, "cube2");
        when(agentManager.send(eq(2L), any(AblestackThirdPartyStagingCommand.class)))
                .thenReturn(new Answer(null, true, "100"));
        for (String type : List.of("NFS", "LOCAL", "GFS2")) {
            settings.put(BackupManager.ThirdPartyStagingStorageType.key(), type);
            assertEquals(100L, service.getAvailableBytes(worker, "/mnt/staging/jobs/ablestack-commvault"));
        }
        ArgumentCaptor<AblestackThirdPartyStagingCommand> commands = ArgumentCaptor.forClass(AblestackThirdPartyStagingCommand.class);
        verify(agentManager, times(3)).send(eq(2L), commands.capture());
        assertEquals(List.of("NFS", "LOCAL", "GFS2"), commands.getAllValues().stream()
                .map(AblestackThirdPartyStagingCommand::getStorageType).collect(java.util.stream.Collectors.toList()));
        verify(agentManager, never()).send(eq(1L), any(AblestackThirdPartyStagingCommand.class));
    }

    @Test
    public void runtimeRejectsOldOrUnconfiguredPathsBeforeContactingHost() throws Exception {
        settings.put(BackupManager.ThirdPartyStagingEnable.key(), "true");

        assertThrows(CloudRuntimeException.class, () -> service.getAvailableBytes(host(1L, "cube1"), "/tmp/mold/backup"));
        verify(agentManager, never()).send(anyLong(), any(AblestackThirdPartyStagingCommand.class));
    }

    @Test
    public void locationChangeSnapshotsLegacyBackupsBeforeSavingNewRoot() {
        Map<String, String> details = legacyBackup();
        when(saveConfiguration.get()).thenAnswer(invocation -> {
            verify(backupDetailsDao).addDetail(eq(7L), eq(ThirdPartyBackupStagingService.SNAPSHOT_KEY), any(String.class), eq(false));
            return "/mnt/new/jobs";
        });
        assertEquals("/mnt/new/jobs", service.updateConfiguration(BackupManager.ThirdPartyStagingRootPath.key(),
                "/mnt/new/jobs", saveConfiguration));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(backupDetailsDao).addDetail(eq(7L), eq(ThirdPartyBackupStagingService.SNAPSHOT_KEY), json.capture(), eq(false));
        ThirdPartyBackupStagingService.Snapshot recorded = new Gson().fromJson(json.getValue(), ThirdPartyBackupStagingService.Snapshot.class);
        assertEquals("/mnt/staging/jobs", recorded.rootPath);
        assertEquals("/mnt/staging", recorded.mountPath);
        assertTrue(details.get(ThirdPartyBackupManifest.DETAIL_KEY).contains("/mnt/staging/jobs/"));
    }

    @Test
    public void queuedRestoreAndUnconfirmedCleanupBlockLocationChanges() {
        Map<String, String> details = legacyBackup();
        for (String key : List.of(ThirdPartyBackupAdmission.BACKUP_KEY, ThirdPartyBackupAdmission.RESTORE_KEY)) {
            details.put(key, "{\"jobId\":\"queued\",\"state\":\"WAITING\"}");
            assertThrows(CloudRuntimeException.class, () -> service.updateConfiguration(
                    BackupManager.ThirdPartyStagingRootPath.key(), "/mnt/new/jobs", saveConfiguration));
            details.remove(key);
        }
        details.put(ThirdPartyBackupRestore.PLAN_KEY, "{}");
        details.put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "WAITING");
        assertThrows(CloudRuntimeException.class, () -> service.updateConfiguration(
                BackupManager.ThirdPartyStagingRootPath.key(), "/mnt/new/jobs", saveConfiguration));
        verify(saveConfiguration, never()).get();
    }

    @Test
    public void queuedBackupsBlockLocationChangesForEveryProviderRegardlessOfDetailVisibility() {
        for (String provider : List.of("ablestack-commvault", "ablestack-netbackup", "ablestack-veeam")) {
            for (boolean display : List.of(false, true)) {
                Map<String, String> details = legacyBackup(provider, display);
                details.put(ThirdPartyBackupAdmission.BACKUP_KEY, "{\"jobId\":\"queued\",\"state\":\"WAITING\"}");
                assertThrows(provider + " display=" + display, CloudRuntimeException.class,
                        () -> service.updateConfiguration(BackupManager.ThirdPartyStagingRootPath.key(),
                                "/mnt/new/jobs", saveConfiguration));
            }
        }
        verify(saveConfiguration, never()).get();
    }

    @Test
    public void recordedCleanupUsesOriginalMountAfterActiveRootMoves() throws Exception {
        ThirdPartyBackupStagingService.Snapshot original = service.captureConfiguration();
        settings.put(BackupManager.ThirdPartyStagingRootPath.key(), "/mnt/new/jobs");
        settings.put(BackupManager.ThirdPartyStagingMountPath.key(), "/mnt/new");
        when(agentManager.send(eq(1L), any(AblestackThirdPartyStagingCommand.class))).thenReturn(new Answer(null, true, "0"));
        service.prepareCleanup(host(1L, "cube1"), "/mnt/staging/jobs/ablestack-veeam", original);
        ArgumentCaptor<AblestackThirdPartyStagingCommand> command = ArgumentCaptor.forClass(AblestackThirdPartyStagingCommand.class);
        verify(agentManager).send(eq(1L), command.capture());
        assertEquals("/mnt/staging", command.getValue().getMountPath());
        assertEquals("/mnt/staging/jobs/ablestack-veeam", command.getValue().getPath());
        assertThrows(CloudRuntimeException.class, () -> service.prepareCleanup(host(1L, "cube1"), "/mnt/new/jobs", original));
    }

    @Test
    public void unknownLegacyMountCannotBeInferredFromNewSettings() {
        Map<String, String> details = legacyBackup();
        details.put(ThirdPartyBackupManifest.DETAIL_KEY, details.get(ThirdPartyBackupManifest.DETAIL_KEY)
                .replace("/mnt/staging/jobs", "/old/unknown"));
        service.updateConfiguration(BackupManager.ThirdPartyStagingRootPath.key(), "/old/unknown", saveConfiguration);
        verify(saveConfiguration).get();
        verify(backupDetailsDao, never()).addDetail(anyLong(), eq(ThirdPartyBackupStagingService.SNAPSHOT_KEY), any(String.class), eq(false));
    }

    private Map<String, String> legacyBackup() {
        return legacyBackup("ablestack-veeam", true);
    }

    private Map<String, String> legacyBackup(String provider, boolean visible) {
        Map<String, String> details = new HashMap<>();
        details.put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
        details.put(ThirdPartyBackupManifest.CLEANUP_STATE_KEY, "COMPLETED");
        details.put(ThirdPartyBackupManifest.DETAIL_KEY, "{\"provider\":\"" + provider + "\",\"backupUuid\":\"backup-old\","
                + "\"vmName\":\"i-2-1-VM\",\"timestamp\":\"2026.10.20\",\"backupType\":\"FULL\",\"volumes\":[{"
                + "\"uuid\":\"volume\",\"provisionedBytes\":100,\"engine\":\"QCOW2\",\"chain\":[{\"backupUuid\":\"backup-old\","
                + "\"path\":\"/mnt/staging/jobs/" + provider + "/i-2-1-VM/2026.10.20/root.volume.qcow2\"}]}]}");
        BackupVO backup = mock(BackupVO.class);
        when(backup.getId()).thenReturn(7L);
        when(backup.getUuid()).thenReturn("backup-old");
        when(backup.getStatus()).thenReturn(Backup.Status.BackedUp);
        when(backup.getDetail(any(String.class))).thenAnswer(invocation -> details.get(invocation.getArgument(0)));
        when(backupDao.findById(7L)).thenReturn(backup);
        // BackupDaoImpl.saveDetails persists this mode with display=true.
        BackupDetailVO mode = new BackupDetailVO(7L, ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE, visible);
        when(backupDetailsDao.findDetails(eq(ThirdPartyBackupManifest.MODE_KEY), eq(ThirdPartyBackupManifest.VOLUME_MODE),
                org.mockito.ArgumentMatchers.nullable(Boolean.class))).thenAnswer(invocation -> {
                    Boolean display = invocation.getArgument(2);
                    return display == null || mode.isDisplay() == display ? List.of(mode) : List.of();
                });
        return details;
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

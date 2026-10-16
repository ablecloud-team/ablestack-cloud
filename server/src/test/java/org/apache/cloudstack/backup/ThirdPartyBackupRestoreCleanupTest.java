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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import com.cloud.host.Host;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ThirdPartyBackupRestoreCleanupTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void mountPreparationDoesNotRecreateAnUnownedRestoreDestination() throws Exception {
        ThirdPartyBackupRestore.Plan plan = new ThirdPartyBackupRestore.Plan();
        plan.jobId = "restore-before-admission";
        plan.hostId = 4L;
        plan.stageRoot = folder.getRoot().getAbsolutePath();
        plan.manifest = new ThirdPartyBackupManifest();
        plan.manifest.setProvider("ablestack-commvault");
        plan.manifest.setBackupUuid("backup-cleanup-test");
        Path providerRoot = Path.of(plan.stageRoot, plan.manifest.getProvider());
        plan.destination = providerRoot.resolve("restore").resolve(plan.jobId).toString();
        ThirdPartyBackupRestore.Operation operation = new ThirdPartyBackupRestore.Operation();
        operation.plan = plan;
        operation.startState = "STARTED";
        BackupVO backup = mock(BackupVO.class);
        when(backup.getId()).thenReturn(1L);
        when(backup.getUuid()).thenReturn(plan.manifest.getBackupUuid());
        when(backup.getDetail(ThirdPartyBackupManifest.MODE_KEY)).thenReturn(ThirdPartyBackupManifest.VOLUME_MODE);
        when(backup.getDetail(ThirdPartyBackupRestore.PLAN_KEY)).thenReturn(new Gson().toJson(plan));
        Host host = mock(Host.class);
        when(host.getId()).thenReturn(plan.hostId);
        BackupDao backups = mock(BackupDao.class);
        when(backups.findById(1L)).thenReturn(backup);
        BackupDetailsDao details = mock(BackupDetailsDao.class);
        when(details.findDetail(1L, ThirdPartyBackupRestore.operationKey(plan.jobId))).thenReturn(
                new BackupDetailVO(1L, ThirdPartyBackupRestore.operationKey(plan.jobId), new Gson().toJson(operation), false));
        when(details.listDetails(1L)).thenReturn(Collections.emptyList());
        ThirdPartyBackupStagingService staging = mock(ThirdPartyBackupStagingService.class);
        when(staging.getStageRootPath(plan.manifest.getProvider())).thenReturn(providerRoot.toString());
        CloudRuntimeException prepared = new CloudRuntimeException("Mount preparation observed");
        doAnswer(invocation -> {
            // The actual staging preparation script runs mkdir -p on its operation path.
            Files.createDirectories(Path.of(invocation.<String>getArgument(1)));
            throw prepared;
        }).when(staging).prepareCleanup(eq(host), anyString());
        ThirdPartyBackupVolumeServiceImpl service = spy(new ThirdPartyBackupVolumeServiceImpl());
        ReflectionTestUtils.setField(service, "backupDao", backups);
        ReflectionTestUtils.setField(service, "backupDetailsDao", details);
        ReflectionTestUtils.setField(service, "stagingService", staging);
        doReturn(host).when(service).getWorkerHost(backup, "RESTORE");

        assertSame(prepared, assertThrows(CloudRuntimeException.class, () ->
                ReflectionTestUtils.invokeMethod(service, "cleanupRestore", backup, host, plan,
                        mock(ThirdPartyBackupVolumeService.RestoreTransfer.class))));
        assertFalse("Mount preparation must leave a missing restore directory absent", Files.exists(Path.of(plan.destination)));
        verify(staging).prepareCleanup(host, providerRoot.toString());
    }
}

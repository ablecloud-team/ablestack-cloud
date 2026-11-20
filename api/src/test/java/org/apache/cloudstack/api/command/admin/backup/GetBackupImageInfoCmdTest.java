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

package org.apache.cloudstack.api.command.admin.backup;

import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.BackupStagingInfoResponse;
import org.apache.cloudstack.backup.BackupManager;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.utils.exception.CloudRuntimeException;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class GetBackupImageInfoCmdTest {
    private final GetBackupImageInfoCmd command = new GetBackupImageInfoCmd();
    private final BackupManager manager = mock(BackupManager.class);

    @Before public void setup() {
        ReflectionTestUtils.setField(command, "backupManager", manager);
        ReflectionTestUtils.setField(command, "backupId", 7L);
    }

    @Test public void returnsSavedImageReferencesThroughTheExistingAdminInspectionChecks() {
        BackupStagingInfoResponse response = new BackupStagingInfoResponse();
        response.provider = "ablestack-netbackup";
        response.hoststate = "COMPLETED";
        response.jobcleanupstate = "COMPLETED";
        when(manager.getBackupStagingInfo(7L, "BACKUP", null)).thenReturn(response);
        command.execute();
        assertSame(response, command.getResponseObject());
        verify(manager).getBackupStagingInfo(7L, "BACKUP", null);
        verifyNoMoreInteractions(manager);
    }

    @Test public void inspectionFailureDoesNotInvokeAnyManagementOrRestoreOperation() {
        when(manager.getBackupStagingInfo(7L, "BACKUP", null)).thenThrow(new CloudRuntimeException("Access denied"));
        assertThrows(ServerApiException.class, command::execute);
        verify(manager).getBackupStagingInfo(7L, "BACKUP", null);
        verifyNoMoreInteractions(manager);
    }

    @Test public void otherProvidersDoNotAcquireTheNetBackupImageView() {
        BackupStagingInfoResponse response = new BackupStagingInfoResponse();
        response.provider = "ablestack-veeam";
        when(manager.getBackupStagingInfo(7L, "BACKUP", null)).thenReturn(response);
        assertThrows(ServerApiException.class, command::execute);
    }
}

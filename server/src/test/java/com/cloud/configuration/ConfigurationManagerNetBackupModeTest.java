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
package com.cloud.configuration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.api.command.admin.config.ResetCfgCmd;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.backup.NetBackupScheduleModeGuard;
import org.apache.cloudstack.backup.NetBackupHookTimeoutService;
import org.apache.cloudstack.config.Configuration;
import org.apache.cloudstack.framework.config.ConfigKey;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

public class ConfigurationManagerNetBackupModeTest {
    private ConfigurationManagerImpl manager;
    private NetBackupScheduleModeGuard guard;
    private static final String KEY = BackupManager.NetBackupVmScheduleEnabled.key();
    @Before public void setup() {
        manager = spy(new ConfigurationManagerImpl());
        guard = mock(NetBackupScheduleModeGuard.class);
        ReflectionTestUtils.setField(manager, "netBackupScheduleModeGuard", guard);
        when(guard.update(anyString(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
    }
    @Test public void ordinaryUpdateMustPassTheGuardBeforeSaving() {
        doReturn("false").when(manager).updateConfigurationInternal(2L, KEY, "Advanced", "false", ConfigKey.Scope.Global, null);
        assertEquals("false", manager.updateConfiguration(2L, KEY, "Advanced", "false", ConfigKey.Scope.Global, null));
        verify(guard).update(org.mockito.ArgumentMatchers.eq("false"), any());
    }
    @Test public void resetUsesTheSameGuardWithTheActualDefault() {
        ResetCfgCmd command = resetCommand();
        Pair<Configuration, String> result = new Pair<>(mock(Configuration.class), "true");
        doReturn(result).when(manager).resetConfigurationInternal(command);
        assertEquals(result, manager.resetConfiguration(command));
        verify(guard).update(org.mockito.ArgumentMatchers.eq("true"), any());
    }
    @Test public void guardRejectionPreventsOrdinaryAndResetWrites() {
        doThrow(new CloudRuntimeException("NETBACKUP_VM_SCHEDULE_MODE_BACKUP_BUSY: work")).when(guard).update(anyString(), any());
        assertThrows(CloudRuntimeException.class, () -> manager.updateConfiguration(2L, KEY, "Advanced", "false", ConfigKey.Scope.Global, null));
        verify(manager, never()).updateConfigurationInternal(2L, KEY, "Advanced", "false", ConfigKey.Scope.Global, null);
        ResetCfgCmd command = resetCommand();
        assertThrows(CloudRuntimeException.class, () -> manager.resetConfiguration(command));
        verify(manager, never()).resetConfigurationInternal(command);
    }
    @Test public void zoneOverridesAreRejectedForBothRoutes() {
        assertThrows(RuntimeException.class, () -> manager.updateConfiguration(2L, KEY, "Advanced", "false", ConfigKey.Scope.Zone, 1L));
        ResetCfgCmd command = resetCommand(); when(command.getZoneId()).thenReturn(1L);
        assertThrows(RuntimeException.class, () -> manager.resetConfiguration(command));
    }
    @Test public void hookTimeoutUpdateAndResetBothSynchronizeHostsBeforeSaving() {
        NetBackupHookTimeoutService sync = mock(NetBackupHookTimeoutService.class);
        ReflectionTestUtils.setField(manager, "netBackupHookTimeoutService", sync);
        when(sync.update(anyString(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        String key = BackupManager.NetBackupHookTimeout.key();
        doReturn("3600").when(manager).updateConfigurationInternal(2L, key, "Advanced", "3600", ConfigKey.Scope.Global, null);
        assertEquals("3600", manager.updateConfiguration(2L, key, "Advanced", "3600", ConfigKey.Scope.Global, null));
        verify(sync).update(org.mockito.ArgumentMatchers.eq("3600"), any());
        ResetCfgCmd command = resetCommand(); when(command.getCfgName()).thenReturn(key);
        Pair<Configuration, String> result = new Pair<>(mock(Configuration.class), "86400");
        doReturn(result).when(manager).resetConfigurationInternal(command);
        assertEquals(result, manager.resetConfiguration(command));
        verify(sync).update(org.mockito.ArgumentMatchers.eq("86400"), any());
        assertThrows(RuntimeException.class, () -> manager.updateConfiguration(2L, key, "Advanced", "3600", ConfigKey.Scope.Zone, 1L));
        when(command.getZoneId()).thenReturn(1L);
        assertThrows(RuntimeException.class, () -> manager.resetConfiguration(command));
    }

    private ResetCfgCmd resetCommand() {
        ResetCfgCmd command = mock(ResetCfgCmd.class);
        when(command.getCfgName()).thenReturn(KEY);
        when(command.getZoneId()).thenReturn(null);
        when(command.getClusterId()).thenReturn(null);
        when(command.getAccountId()).thenReturn(null);
        when(command.getDomainId()).thenReturn(null);
        when(command.getStoragepoolId()).thenReturn(null);
        when(command.getImageStoreId()).thenReturn(null);
        when(command.getManagementServerId()).thenReturn(null);
        return command;
    }
}

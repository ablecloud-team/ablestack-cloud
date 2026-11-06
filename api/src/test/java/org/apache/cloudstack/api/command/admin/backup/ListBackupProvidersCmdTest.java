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
package org.apache.cloudstack.api.command.admin.backup;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.cloudstack.api.response.BackupProviderResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.backup.BackupProvider;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ListBackupProvidersCmdTest {
    private BackupProvider provider(final String name) {
        BackupProvider provider = mock(BackupProvider.class);
        when(provider.getName()).thenReturn(name);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private List<String> names(final Object response) {
        return ((ListResponse<BackupProviderResponse>) response).getResponses().stream()
                .map(BackupProviderResponse::getName).collect(Collectors.toList());
    }

    @Test
    public void testListPreservesBothNasPluginNames() {
        BackupManager manager = mock(BackupManager.class);
        List<BackupProvider> providers = List.of(provider("nas"), provider("ablestack-nas"));
        when(manager.listBackupProviders()).thenReturn(providers);
        ListBackupProvidersCmd cmd = new ListBackupProvidersCmd();
        ReflectionTestUtils.setField(cmd, "backupManager", manager);

        cmd.execute();

        assertEquals(List.of("nas", "ablestack-nas"), names(cmd.getResponseObject()));
    }

    @Test
    public void testNameFilterDoesNotMatchOtherNasPlugin() {
        BackupManager manager = mock(BackupManager.class);
        List<BackupProvider> providers = List.of(provider("nas"), provider("ablestack-nas"));
        when(manager.listBackupProviders()).thenReturn(providers);
        for (String name : List.of("nas", "ablestack-nas")) {
            ListBackupProvidersCmd cmd = new ListBackupProvidersCmd();
            ReflectionTestUtils.setField(cmd, "backupManager", manager);
            ReflectionTestUtils.setField(cmd, "name", name);

            cmd.execute();

            assertEquals(List.of(name), names(cmd.getResponseObject()));
        }
    }

    @Test
    public void testZoneListPreservesBothNasPluginNames() {
        BackupManager manager = mock(BackupManager.class);
        List<BackupProvider> providers = List.of(provider("nas"), provider("ablestack-nas"));
        when(manager.listBackupProvidersForZone(1L)).thenReturn(providers);
        ListBackupProvidersForZoneCmd cmd = new ListBackupProvidersForZoneCmd();
        ReflectionTestUtils.setField(cmd, "backupManager", manager);
        ReflectionTestUtils.setField(cmd, "zoneId", 1L);

        cmd.execute();

        assertEquals(List.of("nas", "ablestack-nas"), names(cmd.getResponseObject()));
    }
}

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
package com.cloud.hypervisor.kvm.resource;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.naming.ConfigurationException;

import com.cloud.agent.properties.AgentProperties;
import com.cloud.agent.properties.AgentPropertiesFileHandler;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.libvirt.Connect;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LibvirtDetachedBackupConfigurationTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void freshDetachedResourceFindsBackupAndCapacityScripts() throws Exception {
        Path scripts = folder.newFolder("kvm").toPath();
        for (String script : new String[] {"ablestack_nasbackup.sh", "ablestack_cvtbackup.sh",
                "ablestack_netbackup.sh", "ablestack_veeam.sh", "thirdparty_restore_capacity.py"}) {
            Files.createFile(scripts.resolve(script));
        }
        Connect connection = mock(Connect.class);
        when(connection.getCapabilities()).thenReturn("<capabilities><guest><arch name='x86_64'><domain type='kvm'>"
                + "<emulator>/usr/bin/qemu-system-x86_64</emulator></domain></arch></guest></capabilities>");
        LibvirtComputingResource resource = new LibvirtComputingResource();
        assertNull(resource.getAbleCvtBackupPath());
        try (MockedStatic<AgentPropertiesFileHandler> properties = Mockito.mockStatic(AgentPropertiesFileHandler.class, Mockito.CALLS_REAL_METHODS);
                MockedStatic<LibvirtConnection> libvirt = Mockito.mockStatic(LibvirtConnection.class);
                MockedConstruction<KVMHAMonitor> monitor = Mockito.mockConstruction(KVMHAMonitor.class);
                MockedConstruction<KVMStoragePoolManager> pools = Mockito.mockConstruction(KVMStoragePoolManager.class)) {
            properties.when(() -> AgentPropertiesFileHandler.getPropertyValue(AgentProperties.KVM_SCRIPTS_DIR)).thenReturn(scripts.toString());
            libvirt.when(LibvirtConnection::getConnection).thenReturn(connection);
            assertTrue(resource.configureForDetachedBackupJob());
            assertEquals(scripts.resolve("ablestack_nasbackup.sh").toString(), resource.getAbleNasBackupPath());
            assertEquals(scripts.resolve("ablestack_cvtbackup.sh").toString(), resource.getAbleCvtBackupPath());
            assertEquals(scripts.resolve("ablestack_netbackup.sh").toString(), resource.getAbleNetBackupPath());
            assertEquals(scripts.resolve("ablestack_veeam.sh").toString(), resource.getAbleVeeamPath());
            assertTrue(Files.isRegularFile(Path.of(resource.getAbleCvtBackupPath()).resolveSibling("thirdparty_restore_capacity.py")));
        }
    }

    @Test
    public void missingBackupScriptFailsConfigurationBeforeContactingLibvirt() throws Exception {
        Path scripts = folder.newFolder("missing-script").toPath();
        Files.createFile(scripts.resolve("ablestack_nasbackup.sh"));
        LibvirtComputingResource resource = new LibvirtComputingResource();
        try (MockedStatic<AgentPropertiesFileHandler> properties = Mockito.mockStatic(AgentPropertiesFileHandler.class, Mockito.CALLS_REAL_METHODS);
                MockedStatic<LibvirtConnection> libvirt = Mockito.mockStatic(LibvirtConnection.class)) {
            properties.when(() -> AgentPropertiesFileHandler.getPropertyValue(AgentProperties.KVM_SCRIPTS_DIR)).thenReturn(scripts.toString());
            assertEquals("Unable to find ablestack_cvtbackup.sh",
                    assertThrows(ConfigurationException.class, resource::configureForDetachedBackupJob).getMessage());
            libvirt.verifyNoInteractions();
        }
    }
}

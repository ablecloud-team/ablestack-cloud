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

package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.cloudstack.backup.AblestackBackupSourceCheckCommand;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;

import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.utils.script.Script;

public class LibvirtAblestackBackupSourceCheckCommandWrapperTest {
    @Rule
    public TemporaryFolder files = new TemporaryFolder();

    private final LibvirtAblestackBackupSourceCheckCommandWrapper wrapper = new LibvirtAblestackBackupSourceCheckCommandWrapper();
    private final LibvirtComputingResource resource = mock(LibvirtComputingResource.class);

    @Test
    public void sourceCheckUsesBoundedReadOnlyProbeAndPreservesPathArguments() throws Exception {
        files.newFile("thirdparty_backup_source_support.py");
        when(resource.getAbleCvtBackupPath()).thenReturn(files.getRoot().toPath().resolve("ablecvt.sh").toString());
        try (MockedConstruction<Script> scripts = mockConstruction(Script.class)) {
            assertTrue(wrapper.execute(new AblestackBackupSourceCheckCommand(List.of("/primary/disk with,comma"), 15), resource).getResult());
            Script script = scripts.constructed().get(0);
            verify(script).add("-k", "5", "15", "python3", files.getRoot().toPath().resolve("thirdparty_backup_source_support.py").toString(), "--health-paths");
            verify(script).add("/primary/disk with,comma");
            verify(script).setAvoidLoggingCommand(true);
        }
    }

    @Test
    public void timeoutAndMissingScriptsAreRejected() throws Exception {
        when(resource.getAbleCvtBackupPath()).thenReturn(files.getRoot().toPath().resolve("ablecvt.sh").toString());
        AblestackBackupSourceCheckCommand command = new AblestackBackupSourceCheckCommand(List.of("/primary/disk"), 15);
        assertFalse(wrapper.execute(command, resource).getResult());
        files.newFile("thirdparty_backup_source_support.py");
        try (MockedConstruction<Script> scripts = mockConstruction(Script.class,
                (script, context) -> when(script.getExitValue()).thenReturn(124))) {
            assertFalse(wrapper.execute(command, resource).getResult());
        }
    }

    @Test
    public void malformedCommandsCannotStartAProbe() {
        when(resource.getAbleCvtBackupPath()).thenReturn("/scripts/ablecvt.sh");
        for (AblestackBackupSourceCheckCommand command : List.of(
                new AblestackBackupSourceCheckCommand(List.of("relative"), 15),
                new AblestackBackupSourceCheckCommand(List.of("/primary/disk"), 0),
                new AblestackBackupSourceCheckCommand(List.of("/primary/disk"), 61),
                new AblestackBackupSourceCheckCommand(List.of(), 15))) {
            assertFalse(wrapper.execute(command, resource).getResult());
        }
    }
}

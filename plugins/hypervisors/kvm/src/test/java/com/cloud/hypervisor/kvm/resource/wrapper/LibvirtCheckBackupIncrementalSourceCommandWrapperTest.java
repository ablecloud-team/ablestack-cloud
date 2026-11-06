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
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.google.gson.JsonParser;
import org.apache.cloudstack.backup.CheckBackupIncrementalSourceCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class LibvirtCheckBackupIncrementalSourceCommandWrapperTest {
    private final LibvirtCheckBackupIncrementalSourceCommandWrapper wrapper = new LibvirtCheckBackupIncrementalSourceCommandWrapper();

    @Test public void inspectionUsesAPrivatePlanAndRemovesItAfterReadingTheProof() throws Exception {
        Path directory = Files.createTempDirectory("incremental-inspection-test-");
        Path script = directory.resolve("thirdparty_volume_backup.py");
        try {
            Files.writeString(script, "import json,sys,os\np=sys.argv[sys.argv.index('--plan-file')+1]\n"
                    + "d=json.load(open(p))\nassert '--inspect-incremental' in sys.argv\n"
                    + "print(json.dumps({'validated':True,'mode':oct(os.stat(p).st_mode & 0o777),'file':p,'vmName':d['vmName']}))\n");
            LibvirtComputingResource resource = mock(LibvirtComputingResource.class);
            when(resource.getAbleCvtBackupPath()).thenReturn(directory.resolve("backup.sh").toString());
            CheckBackupIncrementalSourceCommand command = new CheckBackupIncrementalSourceCommand("vm", true,
                    "QCOW2", List.of(mock(PrimaryDataStoreTO.class)), List.of("root"), "parent", "<domaincheckpoint/>");
            try (MockedStatic<LibvirtAblestackTakeBackupCommandHelper> helper = mockStatic(LibvirtAblestackTakeBackupCommandHelper.class)) {
                helper.when(() -> LibvirtAblestackTakeBackupCommandHelper.resolveDiskPaths(eq(resource), anyList(), anyList()))
                        .thenReturn(List.of("/primary/root"));
                Answer answer = wrapper.execute(command, resource);
                assertTrue(answer.getDetails(), answer.getResult());
                var proof = JsonParser.parseString(answer.getDetails()).getAsJsonObject();
                assertEquals("0o600", proof.get("mode").getAsString());
                assertEquals("vm", proof.get("vmName").getAsString());
                assertFalse(Files.exists(Path.of(proof.get("file").getAsString())));
            }
        } finally { Files.deleteIfExists(script); Files.deleteIfExists(directory); }
    }

    @Test public void unavailableInspectionScriptCannotProduceASuccessAnswer() {
        LibvirtComputingResource resource = mock(LibvirtComputingResource.class);
        when(resource.getAbleCvtBackupPath()).thenReturn("/unavailable/backup.sh");
        CheckBackupIncrementalSourceCommand command = new CheckBackupIncrementalSourceCommand("vm", true,
                "QCOW2", List.of(mock(PrimaryDataStoreTO.class)), List.of("root"), "parent", "<domaincheckpoint/>");
        assertFalse(wrapper.execute(command, resource).getResult());
    }
}

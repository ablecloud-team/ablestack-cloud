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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.cloud.agent.api.AblestackN2KStatusAnswer;
import com.cloud.agent.api.AblestackN2KStatusCommand;
import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class LibvirtAblestackN2KStatusCommandWrapperTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Mock
    private LibvirtComputingResource libvirtComputingResource;

    @Test
    public void executeRejectsMissingWorkdir() {
        LibvirtAblestackN2KStatusCommandWrapper wrapper = new LibvirtAblestackN2KStatusCommandWrapper();
        AblestackN2KStatusCommand cmd = new AblestackN2KStatusCommand("rhel", null);

        Answer answer = wrapper.execute(cmd, libvirtComputingResource);

        Assert.assertFalse(answer.getResult());
        Assert.assertTrue(answer.getDetails().contains("workdir"));
    }

    @Test
    public void executeParsesPhase1CompletedStatusForPhase2Resume() {
        LibvirtAblestackN2KStatusCommandWrapper wrapper = new LibvirtAblestackN2KStatusCommandWrapper();
        AblestackN2KStatusCommand cmd = new AblestackN2KStatusCommand("rhel", "/work/rhel");
        String statusJson = "{"
                + "\"workdir\":\"/work/rhel\","
                + "\"resume\":{\"completed\":false,\"next_step\":\"phase2.cutover\",\"last_step\":\"phase1.sync\",\"percent\":100},"
                + "\"runtime\":{\"split\":{\"phase1\":{\"done\":true},\"phase2\":{\"done\":false}}},"
                + "\"phases\":{}"
                + "}";

        try (MockedConstruction<Script> ignored = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any(OutputInterpreter.class))).thenReturn(statusJson);
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            AblestackN2KStatusAnswer answer = (AblestackN2KStatusAnswer) wrapper.execute(cmd, libvirtComputingResource);

            Assert.assertTrue(answer.getResult());
            Assert.assertEquals("phase1", answer.getPhase());
            Assert.assertEquals("completed", answer.getMigrationState());
            Assert.assertEquals("phase2.cutover", answer.getMigrationStep());
            Assert.assertEquals("100%", answer.getSyncPhysical());
            Assert.assertEquals("/work/rhel", answer.getWorkdir());
            Assert.assertTrue(answer.getStatusJson().contains("\"workdir\":\"/work/rhel\""));
            Script script = ignored.constructed().get(0);
            Mockito.verify(script).add("--workdir", "/work/rhel");
            Mockito.verify(script).add("--json");
            Mockito.verify(script).add("status");
        }
    }

    @Test
    public void executeReportsFailedPhase2InsteadOfTheEarlierCompletedPhase1() throws IOException {
        Path workdir = temporaryFolder.newFolder("phase2-failed").toPath();
        Files.writeString(workdir.resolve("runner.json"), "{\"state\":\"failed\",\"split\":\"phase2\",\"exit_code\":44}");
        AblestackN2KStatusCommand cmd = new AblestackN2KStatusCommand("rhel", workdir.toString());
        String statusJson = "{\"runtime\":{\"split\":{\"phase1\":{\"done\":true}}},\"phases\":{}}";

        try (MockedConstruction<Script> ignored = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any(OutputInterpreter.class))).thenReturn(statusJson);
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            AblestackN2KStatusAnswer answer = (AblestackN2KStatusAnswer) new LibvirtAblestackN2KStatusCommandWrapper().execute(cmd, libvirtComputingResource);

            Assert.assertTrue(answer.getResult());
            Assert.assertEquals("phase2", answer.getPhase());
            Assert.assertEquals("failed", answer.getMigrationState());
            Assert.assertEquals("PHASE2_FAILED", answer.getDisplayStep());
        }
    }

    @Test
    public void executeParsesCloudResultFromRuntimeCloud() {
        LibvirtAblestackN2KStatusCommandWrapper wrapper = new LibvirtAblestackN2KStatusCommandWrapper();
        AblestackN2KStatusCommand cmd = new AblestackN2KStatusCommand("rhel", "/work/rhel");
        String statusJson = "{"
                + "\"workdir\":\"/work/rhel\","
                + "\"resume\":{\"completed\":true,\"next_step\":\"none\",\"last_step\":\"cleanup\",\"percent\":100},"
                + "\"runtime\":{\"split\":{\"phase1\":{\"done\":true},\"phase2\":{\"done\":true}},"
                + "\"cloud\":{\"provider\":\"ablestack-cloud\",\"applied\":true,\"started\":true,\"vm_id\":\"vm-uuid\"}},"
                + "\"target\":{\"format\":\"raw\",\"storage\":\"rbd\"},"
                + "\"phases\":{\"cutover\":{\"done\":true}}"
                + "}";

        try (MockedConstruction<Script> ignored = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any(OutputInterpreter.class))).thenReturn(statusJson);
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            AblestackN2KStatusAnswer answer = (AblestackN2KStatusAnswer) wrapper.execute(cmd, libvirtComputingResource);

            Assert.assertTrue(answer.getResult());
            Assert.assertEquals("phase2", answer.getPhase());
            Assert.assertEquals("completed", answer.getMigrationState());
            Assert.assertEquals("ablestack-cloud", answer.getTargetProvider());
            Assert.assertEquals("vm-uuid", answer.getCloudVmId());
        }
    }

    @Test
    public void executeReportsCompletedCloudCheckpointWhenPhaseTwoMarkerWasNotWritten() {
        AblestackN2KStatusCommand cmd = new AblestackN2KStatusCommand("rhel", "/work/rhel");
        String statusJson = "{\"runtime\":{\"split\":{\"phase1\":{\"done\":true},\"phase2\":{\"done\":false}},"
                + "\"cloud\":{\"provider\":\"ablestack-cloud\",\"checkpoint\":{\"complete\":true,\"vm_id\":\"vm-uuid\"}}}}";

        try (MockedConstruction<Script> ignored = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any(OutputInterpreter.class))).thenReturn(statusJson);
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            AblestackN2KStatusAnswer answer = (AblestackN2KStatusAnswer) new LibvirtAblestackN2KStatusCommandWrapper().execute(cmd, libvirtComputingResource);

            Assert.assertTrue(answer.getResult());
            Assert.assertEquals("phase2", answer.getPhase());
            Assert.assertEquals("completed", answer.getMigrationState());
            Assert.assertEquals("vm-uuid", answer.getCloudVmId());
        }
    }
}

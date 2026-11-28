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

import com.cloud.agent.api.AblestackN2KConvertInstanceCommand;
import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.storage.Storage;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@RunWith(MockitoJUnitRunner.class)
public class LibvirtAblestackN2KConvertInstanceCommandWrapperTest {

    private LibvirtAblestackN2KConvertInstanceCommandWrapper wrapper;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Mock
    private LibvirtComputingResource libvirtComputingResource;
    @Mock
    private KVMStoragePoolManager storagePoolManager;
    @Mock
    private KVMStoragePool storagePool;
    @Mock
    private PrimaryDataStoreTO primaryDataStore;

    @Before
    public void setUp() {
        wrapper = new LibvirtAblestackN2KConvertInstanceCommandWrapper();
        Mockito.when(libvirtComputingResource.getStoragePoolMgr()).thenReturn(storagePoolManager);
        Mockito.when(primaryDataStore.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        Mockito.when(primaryDataStore.getUuid()).thenReturn("rbd-uuid");
        Mockito.when(storagePoolManager.getStoragePool(Storage.StoragePoolType.RBD, "rbd-uuid")).thenReturn(storagePool);
    }

    @Test
    public void executeRejectsRbdCommandWithoutTargetMap() {
        AblestackN2KConvertInstanceCommand cmd = validCommand();
        cmd.setTargetStorage("rbd");
        cmd.setTargetMapJson(null);

        Answer answer = wrapper.execute(cmd, libvirtComputingResource);

        Assert.assertFalse(answer.getResult());
        Assert.assertTrue(answer.getDetails().contains("targetMapJson"));
    }

    @Test
    public void executeRejectsCloudManagedRunWhenSourceApiIsNotV3() {
        AblestackN2KConvertInstanceCommand cmd = validCommand();
        cmd = new AblestackN2KConvertInstanceCommand("rhel", "https://pc:9440", "admin", "secret",
                primaryDataStore, "phase1", "v4", true, "/work/rhel");
        cmd.setTargetFormat("raw");
        cmd.setTargetStorage("rbd");
        cmd.setTargetMapJson("{\"scsi0:0\":\"rbd:rbd/rhel-disk0\"}");

        Answer answer = wrapper.execute(cmd, libvirtComputingResource);

        Assert.assertFalse(answer.getResult());
        Assert.assertTrue(answer.getDetails().contains("sourceApi=v3"));
    }

    @Test
    public void executeBuildsAblestackN2KRunCommandWithoutPassingPlainCredentialsAsArguments() throws IOException {
        String workdir = temporaryFolder.newFolder("rhel").getAbsolutePath();
        AblestackN2KConvertInstanceCommand cmd = validCommand(workdir);

        try (MockedConstruction<Script> ignored = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any(OutputInterpreter.class))).thenReturn("");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            Answer answer = wrapper.execute(cmd, libvirtComputingResource);

            Assert.assertTrue(answer.getResult());
            Script script = ignored.constructed().get(0);
            Mockito.verify(script).add("--workdir", workdir);
            Mockito.verify(script).add("run");
            Mockito.verify(script).add("--vm", "rhel");
            Mockito.verify(script).add("--pc", "https://pc:9440");
            Mockito.verify(script).add(Mockito.eq("--cred-file"), Mockito.anyString());
            Mockito.verify(script).add("--insecure", "1");
            Mockito.verify(script).add("--split", "phase1");
            Mockito.verify(script).add("--shutdown", "guest");
            Mockito.verify(script).add("--source-api", "v3");
            Mockito.verify(script).add("--nfs-host", "10.10.132.11");
            Mockito.verify(script).add("--source-map-from-v3-nfs");
            Mockito.verify(script).add("--target-provider", "ablestack-cloud");
            Mockito.verify(script).add("--target-format", "raw");
            Mockito.verify(script).add("--target-storage", "rbd");
            Mockito.verify(script).add("--dst", "/var/lib/libvirt/images/rhel");
            Mockito.verify(script).add("--cleanup-source-points");
            Mockito.verify(script).add("--target-map-json", "{\"scsi0:0\":\"rbd:rbd/rhel-disk0\"}");
            Mockito.verify(script).add("--start");
            Mockito.verify(script, Mockito.never()).add("--apply");
            Mockito.verify(script).add(Mockito.eq("--cloud-cred-file"), Mockito.anyString());
            Mockito.verify(script).add("--cloud-zone-id", "zone-uuid");
            Mockito.verify(script).add("--cloud-service-offering-id", "service-offering-uuid");
            Mockito.verify(script).add("--cloud-network-ids", "network-uuid");
            Mockito.verify(script).add("--cloud-storage-id", "storage-uuid");
            Mockito.verify(script, Mockito.never()).add(Mockito.contains("secret"));
        }
    }

    @Test
    public void executeRejectsResumeWithoutAnExistingManifest() throws IOException {
        File workdir = temporaryFolder.newFolder("missing-manifest");
        AblestackN2KConvertInstanceCommand cmd = validCommand(workdir.getAbsolutePath());
        cmd.setResume(true);

        Answer answer = wrapper.execute(cmd, libvirtComputingResource);

        Assert.assertFalse(answer.getResult());
        Assert.assertTrue(answer.getDetails().contains("existing migration manifest"));
    }

    @Test
    public void executeRunsTheExistingWorkDirectoryWithoutThePlanOnlyFlag() throws IOException {
        File workdir = temporaryFolder.newFolder("existing-manifest");
        Files.writeString(workdir.toPath().resolve("manifest.json"), "{}");
        AblestackN2KConvertInstanceCommand cmd = validCommand(workdir.getAbsolutePath());
        cmd.setResume(true);

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any(OutputInterpreter.class))).thenReturn("");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            Assert.assertTrue(wrapper.execute(cmd, libvirtComputingResource).getResult());
            Script script = scripts.constructed().get(0);
            Mockito.verify(script).add("--workdir", workdir.getAbsolutePath());
            Mockito.verify(script).add("run");
            Mockito.verify(script, Mockito.never()).add("--resume");
        }
    }

    @Test
    public void executeUsesApplyWithoutStartWhenCloudTargetVmShouldRemainStopped() throws IOException {
        String workdir = temporaryFolder.newFolder("rhel-stopped").getAbsolutePath();
        AblestackN2KConvertInstanceCommand cmd = validCommand(workdir);
        cmd.setStartTargetVm(false);

        try (MockedConstruction<Script> ignored = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any(OutputInterpreter.class))).thenReturn("");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            Answer answer = wrapper.execute(cmd, libvirtComputingResource);

            Assert.assertTrue(answer.getResult());
            Script script = ignored.constructed().get(0);
            Mockito.verify(script).add("--apply");
            Mockito.verify(script, Mockito.never()).add("--start");
        }
    }

    private AblestackN2KConvertInstanceCommand validCommand() {
        return validCommand("/work/rhel");
    }

    private AblestackN2KConvertInstanceCommand validCommand(String workdir) {
        AblestackN2KConvertInstanceCommand cmd = new AblestackN2KConvertInstanceCommand("rhel", "https://pc:9440",
                "admin", "secret", primaryDataStore, "phase1", "v3", true, workdir);
        cmd.setTargetFormat("raw");
        cmd.setTargetStorage("rbd");
        cmd.setTargetMapJson("{\"scsi0:0\":\"rbd:rbd/rhel-disk0\"}");
        cmd.setNfsHost("10.10.132.11");
        cmd.setTargetProvider("ablestack-cloud");
        cmd.setCloudEndpoint("http://10.10.22.10:8080/client/api");
        cmd.setCloudApiKey("api-key");
        cmd.setCloudSecretKey("cloud-secret");
        cmd.setCloudZoneId("zone-uuid");
        cmd.setCloudServiceOfferingId("service-offering-uuid");
        cmd.setCloudNetworkIds("network-uuid");
        cmd.setCloudStorageId("storage-uuid");
        return cmd;
    }
}

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
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.nio.charset.StandardCharsets;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.KvmQ35PcieTopology;
import com.cloud.vm.VmDetailConstants;
import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LibvirtQ35PcieTopologyTest {
    private static final String UUID = "d69a6dc5-1f35-442d-8941-e0b3e02e4f63";
    private static final String NAME = "i-2-test-VM";
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private String boot(String uuid, int ports) {
        StringBuilder log = new StringBuilder("2026-10-19 05:40:15.354+0000: starting up libvirt version: 10.10.0\n");
        log.append("-uuid ").append(uuid).append(" \\\n");
        log.append("-machine pc-q35-rhel9.6.0,usb=off \\\n");
        for (int i = 0; i < ports; i++) { log.append("-device '{\"driver\":\"pcie-root-port\"}' \\\n"); }
        log.append("-msg timestamp=on\n");
        return log.toString();
    }

    @Test public void readsLegacyAndReservedLaunches() {
        assertEquals(KvmQ35PcieTopology.LEGACY, LibvirtQ35PcieTopology.fromLogBlock(boot(UUID, 6), UUID));
        assertEquals(KvmQ35PcieTopology.RESERVED_32, LibvirtQ35PcieTopology.fromLogBlock(boot(UUID, 32), UUID));
    }
    @Test public void ignoresAnotherVmEvenIfItsNameWasReused() {
        assertNull(LibvirtQ35PcieTopology.fromLogBlock(boot("another-uuid", 6), UUID));
    }
    @Test public void incompleteLaunchCannotBeMistakenForLegacyHardware() {
        assertNull(LibvirtQ35PcieTopology.fromLogBlock(boot(UUID, 32).replace("-msg timestamp=on", ""), UUID));
        assertNull(LibvirtQ35PcieTopology.fromLogBlock(boot(UUID, 32).substring(boot(UUID, 32).indexOf('\n') + 1), UUID));
        assertNull(LibvirtQ35PcieTopology.fromLogBlock("-uuid ", UUID));
    }
    @Test public void detectsExistingLegacyVmEvenAfterAnAutomatic32PortStart() throws Exception {
        Files.writeString(folder.getRoot().toPath().resolve(NAME + ".log"), boot(UUID, 6) + boot(UUID, 32));
        assertEquals(KvmQ35PcieTopology.LEGACY, LibvirtQ35PcieTopology.find(null, NAME, UUID, folder.getRoot().toPath()));
    }
    @Test public void preservesNew32PortVmsWithoutRequiringAnInventoryOfUuids() throws Exception {
        Files.writeString(folder.getRoot().toPath().resolve(NAME + ".log"), boot(UUID, 32) + boot(UUID, 32));
        assertEquals(KvmQ35PcieTopology.RESERVED_32, LibvirtQ35PcieTopology.find(null, NAME, UUID, folder.getRoot().toPath()));
    }
    @Test public void readsRotatedCompressedHistory() throws Exception {
        Path directory = folder.getRoot().toPath();
        Files.writeString(directory.resolve(NAME + ".log"), boot(UUID, 32));
        try (var output = new GZIPOutputStream(Files.newOutputStream(directory.resolve(NAME + ".log.1.gz")))) {
            output.write(boot(UUID, 6).getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(KvmQ35PcieTopology.LEGACY, LibvirtQ35PcieTopology.find(null, NAME, UUID, directory));
    }
    @Test public void missingHistoryCannotBeMistakenForLegacyOr32Ports() {
        assertNull(LibvirtQ35PcieTopology.find(null, NAME, UUID, folder.getRoot().toPath()));
    }
    @Test public void lookupRejectsPathTraversal() {
        assertThrows(CloudRuntimeException.class, () -> LibvirtQ35PcieTopology.find(null, "../vm", UUID, folder.getRoot().toPath()));
    }
    @Test public void domainXmlMustBelongToTheSameVm() {
        String xml = "<domain><uuid>" + UUID + "</uuid><os><type machine='q35'>hvm</type></os><devices>"
                + "<controller type='pci' model='pcie-root-port'/></devices></domain>";
        assertEquals(KvmQ35PcieTopology.LEGACY, LibvirtQ35PcieTopology.fromXml(xml, UUID));
        assertNull(LibvirtQ35PcieTopology.fromXml(xml, "other-uuid"));
    }
    @Test public void explicitPolicyWinsOverOldLocalHistoryAndIsReturnedInVmDetails() {
        VirtualMachineTO spec = new Gson().fromJson("{\"type\":\"User\"}", VirtualMachineTO.class);
        spec.setDetails(Map.of(VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY, "reserved32"));
        LibvirtVMDef vm = new LibvirtVMDef();
        var guest = new LibvirtVMDef.GuestDef();
        guest.setGuestType(LibvirtVMDef.GuestDef.GuestType.KVM);
        guest.setMachineType("q35");
        var devices = new LibvirtVMDef.DevicesDef();
        devices.setGuestType(LibvirtVMDef.GuestDef.GuestType.KVM);
        vm.addComp(guest); vm.addComp(devices);
        assertFalse(vm.toString().contains("model='pcie-root-port'"));
        LibvirtQ35PcieTopology.prepare(spec, vm, null);
        assertTrue(vm.toString().contains("model='pcie-root-port'"));
        assertEquals("reserved32", spec.getDetails().get(VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY));
    }
    @Test public void discoveredPolicyIsAppliedAndReturnedForPersistence() throws Exception {
        Path directory = folder.getRoot().toPath();
        Files.writeString(directory.resolve(NAME + ".log"), boot(UUID, 32));
        VirtualMachineTO spec = unmarkedVm();
        LibvirtVMDef vm = q35();
        LibvirtQ35PcieTopology.prepare(spec, vm, null, directory);
        assertTrue(vm.toString().contains("model='pcie-root-port'"));
        assertEquals("reserved32", spec.getDetails().get(VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY));
    }
    @Test public void unknownHistoryBlocksStartBeforeChangingHardware() {
        VirtualMachineTO spec = unmarkedVm();
        LibvirtVMDef vm = q35();
        CloudRuntimeException error = assertThrows(CloudRuntimeException.class,
                () -> LibvirtQ35PcieTopology.prepare(spec, vm, null, folder.getRoot().toPath()));
        assertTrue(error.getMessage().contains(LibvirtQ35PcieTopology.UNKNOWN));
        assertFalse(vm.toString().contains("model='pcie-root-port'"));
        assertTrue(spec.getDetails() == null || !spec.getDetails().containsKey(VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY));
    }

    private VirtualMachineTO unmarkedVm() {
        return new Gson().fromJson("{\"type\":\"User\",\"name\":\"" + NAME + "\",\"uuid\":\"" + UUID + "\"}", VirtualMachineTO.class);
    }

    private LibvirtVMDef q35() {
        LibvirtVMDef vm = new LibvirtVMDef();
        var guest = new LibvirtVMDef.GuestDef();
        guest.setGuestType(LibvirtVMDef.GuestDef.GuestType.KVM);
        guest.setMachineType("q35");
        var devices = new LibvirtVMDef.DevicesDef();
        devices.setGuestType(LibvirtVMDef.GuestDef.GuestType.KVM);
        vm.addComp(guest); vm.addComp(devices);
        vm.toString();
        return vm;
    }
}


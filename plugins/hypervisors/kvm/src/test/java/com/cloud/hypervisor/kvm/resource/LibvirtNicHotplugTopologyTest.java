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

package com.cloud.hypervisor.kvm.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LibvirtNicHotplugTopologyTest {
    private String domain(
            String machine,
            String emulator,
            LibvirtVMDef.GuestDef.GuestType type,
            boolean reverse) {
        return domain(machine, emulator, type, reverse, "nic-test", null);
    }

    private String domain(String machine, String emulator, LibvirtVMDef.GuestDef.GuestType type,
            boolean reverse, String name, String uuid) {
        return domain(machine, emulator, type, reverse, name, uuid, true);
    }

    private String domain(String machine, String emulator, LibvirtVMDef.GuestDef.GuestType type,
            boolean reverse, String name, String uuid, boolean reservePorts) {
        LibvirtVMDef vm = new LibvirtVMDef();
        vm.setHvsType("kvm");
        vm.setDomainName(name);
        vm.setDomUUID(uuid);
        LibvirtVMDef.GuestDef guest = new LibvirtVMDef.GuestDef();
        guest.setGuestType(type);
        guest.setMachineType(machine);
        LibvirtVMDef.DevicesDef devices = new LibvirtVMDef.DevicesDef();
        devices.setReserveQ35PciePorts(reservePorts);
        devices.setGuestType(type);
        devices.setEmulatorPath(emulator);
        if (reverse) {
            vm.addComp(devices);
            vm.addComp(guest);
        } else {
            vm.addComp(guest);
            vm.addComp(devices);
        }
        String xml = vm.toString();
        assertEquals(xml, vm.toString());
        return xml;
    }

    private int ports(String xml) {
        return xml.split("model='pcie-root-port'", -1).length - 1;
    }

    @Test
    public void x86Q35ReservesPortsBeyondBootDevices() {
        String xml =
                domain("q35", "/usr/libexec/qemu-kvm", LibvirtVMDef.GuestDef.GuestType.KVM, false);
        assertEquals(32, ports(xml));
        assertTrue(xml.contains("model='pcie-root'"));
        assertTrue(xml.contains("model='pcie-to-pci-bridge'"));
    }

    @Test
    public void canonicalVersionedQ35AlsoReservesPorts() {
        assertEquals(
                32,
                ports(
                        domain(
                                "pc-q35-rhel9.8.0",
                                "/usr/libexec/qemu-kvm",
                                LibvirtVMDef.GuestDef.GuestType.KVM,
                                false)));
    }

    @Test
    public void componentInsertionOrderCannotLoseMachineType() {
        assertEquals(32, ports(domain("q35", null, LibvirtVMDef.GuestDef.GuestType.KVM, true)));
    }

    @Test
    public void i440fxPciTopologyIsUnchanged() {
        assertEquals(
                0,
                ports(
                        domain(
                                "pc-i440fx-rhel7.6.0",
                                "/usr/libexec/qemu-kvm",
                                LibvirtVMDef.GuestDef.GuestType.KVM,
                                false)));
    }

    @Test
    public void pcDefaultIsUnchanged() {
        assertEquals(
                0,
                ports(
                        domain(
                                "pc",
                                "/usr/libexec/qemu-kvm",
                                LibvirtVMDef.GuestDef.GuestType.KVM,
                                false)));
    }

    @Test
    public void lxcDoesNotReceiveX86PcieControllers() {
        assertEquals(
                0,
                ports(
                        domain(
                                "q35",
                                "/usr/bin/qemu-system-x86_64",
                                LibvirtVMDef.GuestDef.GuestType.LXC,
                                false)));
    }

    @Test
    public void aarch64ExistingPortsAreNotDuplicated() {
        assertEquals(
                32,
                ports(
                        domain(
                                "virt",
                                "/usr/bin/qemu-system-aarch64",
                                LibvirtVMDef.GuestDef.GuestType.KVM,
                                false)));
    }

    @Test
    public void s390xExistingPortsAreUnchanged() {
        assertEquals(
                32,
                ports(
                        domain(
                                "virt",
                                "/usr/bin/qemu-system-s390x",
                                LibvirtVMDef.GuestDef.GuestType.KVM,
                                false)));
    }

    @Test
    public void allExistingVmsCanKeepLegacyQ35AutoAllocation() {
        assertEquals(0, ports(domain("pc-q35-rhel9.6.0", "/usr/libexec/qemu-kvm",
                LibvirtVMDef.GuestDef.GuestType.KVM, false,
                "any-existing-vm", "any-existing-uuid", false)));
    }

    @Test
    public void legacyPolicyAlsoWorksWithReversedComponentOrder() {
        assertEquals(0, ports(domain("pc-q35-rhel9.6.0", "/usr/libexec/qemu-kvm",
                LibvirtVMDef.GuestDef.GuestType.KVM, true,
                "another-existing-vm", "another-existing-uuid", false)));
    }

    @Test
    public void explicitReservationDoesNotDependOnVmIdentity() {
        assertEquals(32, ports(domain("q35", "/usr/libexec/qemu-kvm",
                LibvirtVMDef.GuestDef.GuestType.KVM, false, "i-2-218-VM", "another-vm-uuid")));
    }

    @Test
    public void allNewVmIdentitiesCanUseReservedPorts() {
        assertEquals(32, ports(domain("q35", "/usr/libexec/qemu-kvm",
                LibvirtVMDef.GuestDef.GuestType.KVM, false,
                "i-2-999-VM", "9ec8f15a-c7dc-4600-8612-ceee9c870236")));
    }

    @Test
    public void reusingDeviceDefinitionChangesTopologyOnlyWhenPolicyChanges() {
        LibvirtVMDef vm = new LibvirtVMDef();
        vm.setHvsType("kvm");
        vm.setDomainName("i-2-218-VM");
        vm.setDomUUID("d69a6dc5-1f35-442d-8941-e0b3e02e4f63");
        LibvirtVMDef.GuestDef guest = new LibvirtVMDef.GuestDef();
        guest.setGuestType(LibvirtVMDef.GuestDef.GuestType.KVM);
        guest.setMachineType("q35");
        LibvirtVMDef.DevicesDef devices = new LibvirtVMDef.DevicesDef();
        devices.setGuestType(LibvirtVMDef.GuestDef.GuestType.KVM);
        vm.addComp(guest);
        vm.addComp(devices);
        assertEquals(0, ports(vm.toString()));
        vm.setDomainName("normal-vm");
        assertEquals(0, ports(vm.toString()));
        devices.setReserveQ35PciePorts(true);
        assertEquals(32, ports(vm.toString()));
    }
}

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
package com.cloud.hypervisor.kvm.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.cloud.storage.MigrationDiskControllerPolicy;

import org.junit.Test;

import java.util.List;

public class MigrationDiskControllerGuardTest {
    private static final String PLAN =
            "{\"version\":1,\"policy\":\"auto\",\"machine\":\"q35\",\"firmware\":\"bios\",\"root_selection\":\"source_disk_label\",\"source_vm_uuid\":\"source-uuid\",\"disks\":[{\"index\":0,\"disk_id\":\"ide0:0\",\"device_key\":\"2000\",\"kind\":\"ide\",\"bus\":0,\"unit\":0,\"size_bytes\":1073741824,\"role\":\"root\",\"target\":{\"bus\":\"sata\",\"model\":\"ahci\",\"device\":\"sda\"}},{\"index\":1,\"disk_id\":\"scsi0:0\",\"device_key\":\"2001\",\"kind\":\"scsi\",\"bus\":0,\"unit\":0,\"size_bytes\":1073741824,\"role\":\"data\",\"target\":{\"bus\":\"scsi\",\"model\":\"virtio-scsi\",\"device\":\"sdb\"}}],\"hash\":\"143c43746c7017fd9c613629c42061fb6b3b06dbcab44d65a8ae5b9ca2e26e1f\"}";
    private static final String BINDINGS =
            "[{\"id\":\"root\",\"path\":\"/mnt/pool/root\",\"format\":\"qcow2\",\"storage\":\"file\"},{\"id\":\"data\",\"path\":\"/mnt/pool/data\",\"format\":\"qcow2\",\"storage\":\"file\"}]";
    private static final String XML =
            "<domain><os><type machine='pc-q35-9.0'>hvm</type></os><devices><controller type='sata' index='0'/><controller type='scsi' index='0' model='virtio-scsi'/><disk device='disk'><driver type='qcow2'/><source file='/mnt/pool/root'/><target dev='sda' bus='sata'/><address type='drive' controller='0' bus='0' target='0' unit='0'/></disk><disk device='disk'><driver type='qcow2'/><source file='/mnt/pool/data'/><target dev='sdb' bus='scsi'/><address type='drive' controller='0' bus='0' target='0' unit='0'/></disk></devices></domain>";

    @Test
    public void separateMemoryBalloonDeviceSectionCannotHideWritableDisks() {
        var guard = new MigrationDiskControllerGuard();
        String input =
                XML.replace("<devices>", "<devices><memballoon model='none'/></devices><devices>");
        String normalized = guard.normalizeDeviceSections(input);
        guard.validate(PLAN, BINDINGS, normalized, true);
        assertTrue(normalized.contains("memballoon"));
    }

    @Test
    public void deviceMetadataIsNotTheDomainDeviceList() {
        new MigrationDiskControllerGuard()
                .validate(
                        PLAN,
                        BINDINGS,
                        XML.replace(
                                "<domain>",
                                "<domain><metadata><devices><device id='root'/></devices></metadata>"),
                        true);
    }

    @Test
    public void finalXmlProvesDriverAndStableVolumeMapping() {
        var result = new MigrationDiskControllerGuard().validate(PLAN, BINDINGS, XML, true);
        assertEquals(
                MigrationDiskControllerPolicy.parse(PLAN).get("hash").getAsString(),
                result.get("planhash"));
        assertEquals(2, ((List<?>) result.get("disks")).size());
    }

    @Test
    public void xmlHookCannotReplaceDriverPathOrFormat() {
        for (String bad :
                List.of(
                        XML.replace("bus='sata'", "bus='ide'"),
                        XML.replace("/mnt/pool/data", "/mnt/pool/other"),
                        XML.replace("type='qcow2'", "type='raw'"),
                        XML.replace("model='virtio-scsi'", "model='lsilogic'"),
                        XML.replace("pc-q35-9.0", "pc-i440fx-9.0")))
            reject(() -> new MigrationDiskControllerGuard().validate(PLAN, BINDINGS, bad, true));
    }

    @Test
    public void normalizationMustProvideActualController() {
        String input = XML.replace("<controller type='sata' index='0'/>", "");
        new MigrationDiskControllerGuard().validate(PLAN, BINDINGS, input, false);
        reject(() -> new MigrationDiskControllerGuard().validate(PLAN, BINDINGS, input, true));
    }

    @Test
    public void parserDoesNotResolveExternalEntities() {
        reject(
                () ->
                        new MigrationDiskControllerGuard()
                                .validate(
                                        PLAN,
                                        BINDINGS,
                                        "<!DOCTYPE x [<!ENTITY external SYSTEM 'file:///nonexistent'>]>"
                                                + XML,
                                        true));
    }

    @Test
    public void unusedVirtioControllerCannotHideTheActualLsiController() {
        String bad =
                XML.replace(
                        "<controller type='scsi' index='0' model='virtio-scsi'/>",
                        "<controller type='scsi' index='0' model='lsilogic'/><controller type='scsi' index='1' model='virtio-scsi'/>");
        reject(() -> new MigrationDiskControllerGuard().validate(PLAN, BINDINGS, bad, true));
        reject(
                () ->
                        new MigrationDiskControllerGuard()
                                .validate(
                                        PLAN,
                                        BINDINGS,
                                        XML.replace(
                                                "<disk device='disk'>",
                                                "<disk device='disk'><readonly/>"),
                                        true));
    }

    @Test
    public void nativeCapabilitiesMustIncludeRequestedModels() {
        String caps =
                "<domainCapabilities><path>/usr/libexec/qemu-kvm</path><machine>pc-q35-9.0</machine><os supported='yes'><enum name='firmware'><value>efi</value><value>bios</value></enum></os><devices><disk supported='yes'><enum name='bus'><value>sata</value><value>scsi</value></enum></disk></devices></domainCapabilities>";
        var guard =
                new MigrationDiskControllerGuard(
                        args ->
                                args.get(0).equals("virsh")
                                        ? caps
                                        : "name virtio-scsi-pci, name ich9-ahci");
        guard.qualify(MigrationDiskControllerPolicy.parse(PLAN));
        var missing =
                new MigrationDiskControllerGuard(
                        args -> args.get(0).equals("virsh") ? caps : "name ich9-ahci");
        reject(() -> missing.qualify(MigrationDiskControllerPolicy.parse(PLAN)));
    }

    private void reject(Runnable action) {
        try {
            action.run();
            fail("Expected controller rejection");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}

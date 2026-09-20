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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.junit.Test;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.w3c.dom.Element;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.exception.InternalErrorException;
import com.cloud.storage.Volume;
import org.apache.cloudstack.storage.to.TemplateObjectTO;

public class ConfigDriveMediaTest {
    private static final String VM = "i-2-5-VM";
    private static final String PATH = "/mnt/secondary/configdrive/" + VM + ".iso";
    private String disk(String path, String bus) {
        return "<disk type='file' device='cdrom'><driver name='qemu' type='raw'/>"
                + (path == null ? "" : "<source file='" + path + "'/>")
                + "<target dev='sdb' bus='" + bus + "'/><readonly/><address type='drive' controller='0' bus='0' target='0' unit='1'/></disk>";
    }
    private String domain(String disks) {
        return "<domain><devices>" + disks + "</devices></domain>";
    }
    @Test public void emptyAndOrdinaryCdromsAreNotConfigDrive() throws Exception {
        for (String path : new String[]{null, "/isos/rocky.iso", "/isos/" + VM + ".iso", PATH + ".backup"}) {
            assertNull(ConfigDriveDiskUtil.findMedia(domain(disk(path, "ide")), VM));
        }
    }
    @Test public void verifiesNfsPoolSourceRatherThanMountDirectoryShape() throws Exception {
        String mount = "/mnt/006ac919-7755-3e59-9cd8-9b906a9e22e8";
        String path = mount + "/" + VM + ".iso";
        String xml = "<pool><source><dir path='/nfs/secondary/configdrive'/></source><target><path>" + mount + "</path></target></pool>";
        assertTrue(ConfigDriveDiskUtil.isConfigDrivePoolPath(path, VM, xml));
        Connect conn = mock(Connect.class);
        org.libvirt.StoragePool pool = mock(org.libvirt.StoragePool.class);
        when(conn.storagePoolLookupByUUIDString("006ac919-7755-3e59-9cd8-9b906a9e22e8")).thenReturn(pool);
        when(pool.getXMLDesc(0)).thenReturn(xml);
        assertNotNull(ConfigDriveDiskUtil.findMedia(domain(disk(path, "ide")), VM, conn));
        verify(pool).free();
        assertFalse(ConfigDriveDiskUtil.isConfigDrivePoolPath(path, VM, xml.replace("/configdrive", "/ordinary-isos")));
        assertFalse(ConfigDriveDiskUtil.isConfigDrivePoolPath(path, VM, xml.replace(mount, "/mnt/other")));
        assertNull(ConfigDriveDiskUtil.findMedia(domain(disk(path, "ide")), VM));
    }
    @Test public void mediaUpdatePreservesBusesAndAddresses() throws Exception {
        for (String bus : new String[]{"ide", "sata", "scsi"}) {
            Element media = ConfigDriveDiskUtil.findMedia(domain(disk(PATH, bus)), VM);
            String attached = ConfigDriveDiskUtil.mediaXml(media, false);
            String empty = ConfigDriveDiskUtil.mediaXml(media, true);
            assertTrue(attached.contains(PATH));
            assertTrue(attached.contains("bus=\"" + bus + "\""));
            assertTrue(empty.contains("bus=\"" + bus + "\""));
            assertTrue(empty.contains("address"));
            assertFalse(empty.contains("<source"));
            assertTrue(ConfigDriveDiskUtil.mediaXml(media, false).contains(PATH));
        }
    }
    @Test(expected = InternalErrorException.class) public void duplicateMediaFails() throws Exception {
        ConfigDriveDiskUtil.findMedia(domain(disk(PATH, "ide") + disk(PATH, "sata")), VM);
    }
    @Test public void absentConfigDriveSkipsStorageAndDeviceChanges() throws Exception {
        LibvirtComputingResource resource = mock(LibvirtComputingResource.class, CALLS_REAL_METHODS);
        Connect conn = mock(Connect.class); Domain dm = mock(Domain.class);
        doReturn(dm).when(resource).getDomain(conn, VM);
        when(dm.getXMLDesc(0)).thenReturn(domain(disk(null, "ide") + disk("/isos/user.iso", "ide")));
        VirtualMachineTO vm = mock(VirtualMachineTO.class);
        when(vm.getDisks()).thenReturn(new DiskTO[]{new DiskTO(null, 4L, null, Volume.Type.ISO)});
        resource.detachAndAttachConfigDriveISO(conn, VM, vm);
        verify(resource, never()).getVolumePath(any(), any(), anyBoolean());
        verify(resource, never()).attachOrDetachDevice(any(), anyBoolean(), anyString(), anyString());
    }
    @Test public void failedReattachRestoresMediaAndPropagatesFailure() throws Exception {
        LibvirtComputingResource resource = mock(LibvirtComputingResource.class, CALLS_REAL_METHODS);
        Connect conn = mock(Connect.class);
        doReturn(null, "reattach failed", null).when(resource).attachOrDetachDevice(eq(conn), eq(true), eq(VM), anyString());
        Element media = ConfigDriveDiskUtil.findMedia(domain(disk(PATH, "sata")), VM);
        try {
            resource.refreshConfigDriveMedia(conn, VM, media);
            fail("Failure must reach the migration/NIC command");
        } catch (InternalErrorException expected) {
            assertTrue(expected.getMessage().contains("reattach failed"));
        }
        verify(resource, times(2)).attachOrDetachDevice(conn, true, VM, ConfigDriveDiskUtil.mediaXml(media, false));
    }
    @Test public void validTransferDiskCanUseHostCacheWithoutDatastore() throws Exception {
        TemplateObjectTO data = new TemplateObjectTO(); data.setPath("configdrive/" + VM + ".iso");
        DiskTO disk = new DiskTO(data, 4L, data.getPath(), Volume.Type.ISO);
        VirtualMachineTO vm = mock(VirtualMachineTO.class); when(vm.getDisks()).thenReturn(new DiskTO[]{disk});
        assertSame(disk, ConfigDriveDiskUtil.findDisk(vm, VM));
    }
}

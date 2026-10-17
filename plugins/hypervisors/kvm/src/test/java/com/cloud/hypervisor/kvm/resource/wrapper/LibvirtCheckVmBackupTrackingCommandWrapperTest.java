//
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
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.Error;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckVmBackupTrackingCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.google.gson.JsonParser;

public class LibvirtCheckVmBackupTrackingCommandWrapperTest {
    private LibvirtCheckVmBackupTrackingCommandWrapper wrapper;
    private LibvirtComputingResource resource;
    private LibvirtUtilitiesHelper helper;
    private Connect connection;
    private VolumeObjectTO volume;

    @Before
    public void setup() throws Exception {
        wrapper = spy(new LibvirtCheckVmBackupTrackingCommandWrapper());
        resource = mock(LibvirtComputingResource.class);
        helper = mock(LibvirtUtilitiesHelper.class);
        connection = mock(Connect.class);
        volume = mock(VolumeObjectTO.class);
        when(resource.getLibvirtUtilitiesHelper()).thenReturn(helper);
        when(helper.getConnection()).thenReturn(connection);
        when(volume.getPath()).thenReturn("disk.qcow2");
        KVMStoragePool pool = mock(KVMStoragePool.class);
        when(helper.getPrimaryPoolFromDataTo(eq(volume), any())).thenReturn(pool);
        when(pool.getLocalPathFor("disk.qcow2")).thenReturn("/pool/disk.qcow2");
    }

    private void missingDomain() throws Exception {
        LibvirtException failure = mock(LibvirtException.class);
        Error error = mock(Error.class);
        when(failure.getError()).thenReturn(error);
        when(error.getCode()).thenReturn(Error.ErrorNumber.VIR_ERR_NO_DOMAIN);
        when(resource.getDomain(connection, "vm")).thenThrow(failure);
    }

    private CheckVmBackupTrackingCommand stoppedCommand() {
        return new CheckVmBackupTrackingCommand("vm", true, Collections.singletonList(volume));
    }

    @Test
    public void stoppedVmWithoutDomainInspectsBackingChain() throws Exception {
        missingDomain();
        doReturn("[{\"format\":\"qcow2\"}]").when(wrapper).run("qemu-img", "info", "--output=json", "--backing-chain", "/pool/disk.qcow2");
        assertTrue(wrapper.execute(stoppedCommand(), resource).getResult());
        verify(wrapper).run("qemu-img", "info", "--output=json", "--backing-chain", "/pool/disk.qcow2");
        verify(wrapper, never()).run(eq("virsh"), any(String[].class));
    }

    @Test
    public void bitmapOnSecondVolumeBlocksStoppedVmWithoutDomain() throws Exception {
        missingDomain();
        VolumeObjectTO secondVolume = mock(VolumeObjectTO.class);
        KVMStoragePool secondPool = mock(KVMStoragePool.class);
        when(secondVolume.getPath()).thenReturn("data.qcow2");
        when(helper.getPrimaryPoolFromDataTo(eq(secondVolume), any())).thenReturn(secondPool);
        when(secondPool.getLocalPathFor("data.qcow2")).thenReturn("/pool/data.qcow2");
        doReturn("[{\"format\":\"qcow2\"}]").when(wrapper).run("qemu-img", "info", "--output=json", "--backing-chain", "/pool/disk.qcow2");
        doReturn("[{\"format\":\"qcow2\"},{\"format-specific\":{\"data\":{\"bitmaps\":[{\"name\":\"backup\"}]}}}]")
                .when(wrapper).run("qemu-img", "info", "--output=json", "--backing-chain", "/pool/data.qcow2");
        Answer answer = wrapper.execute(new CheckVmBackupTrackingCommand("vm", true, Arrays.asList(volume, secondVolume)), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().startsWith("BACKUP_TRACKING_EXISTS:"));
    }

    @Test
    public void missingDomainForRunningVmRemainsUnknown() throws Exception {
        missingDomain();
        Answer answer = wrapper.execute(new CheckVmBackupTrackingCommand("vm", false, Collections.singletonList(volume)), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().startsWith("BACKUP_TRACKING_UNKNOWN:"));
        verify(wrapper, never()).run(eq("qemu-img"), any(String[].class));
    }

    @Test
    public void stoppedVmWithoutVolumesRemainsUnknown() throws Exception {
        missingDomain();
        Answer answer = wrapper.execute(new CheckVmBackupTrackingCommand("vm", true, Collections.emptyList()), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().startsWith("BACKUP_TRACKING_UNKNOWN:"));
    }

    @Test
    public void connectionFailureDoesNotUseOfflineFallback() throws Exception {
        when(resource.getDomain(connection, "vm")).thenThrow(mock(LibvirtException.class));
        Answer answer = wrapper.execute(stoppedCommand(), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().startsWith("BACKUP_TRACKING_UNKNOWN:"));
        verify(wrapper, never()).run(eq("qemu-img"), any(String[].class));
    }

    @Test
    public void unreadableOfflineDiskRemainsUnknown() throws Exception {
        missingDomain();
        doThrow(new IllegalStateException("image is locked")).when(wrapper).run("qemu-img", "info", "--output=json", "--backing-chain", "/pool/disk.qcow2");
        Answer answer = wrapper.execute(stoppedCommand(), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().startsWith("BACKUP_TRACKING_UNKNOWN:"));
    }

    @Test
    public void emptyBackingChainRemainsUnknown() throws Exception {
        missingDomain();
        doReturn("[]").when(wrapper).run("qemu-img", "info", "--output=json", "--backing-chain", "/pool/disk.qcow2");
        Answer answer = wrapper.execute(stoppedCommand(), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().startsWith("BACKUP_TRACKING_UNKNOWN:"));
    }

    @Test
    public void definedVmStillChecksCheckpoints() throws Exception {
        Domain domain = mock(Domain.class);
        when(resource.getDomain(connection, "vm")).thenReturn(domain);
        doReturn("backup-checkpoint\n").when(wrapper).run("virsh", "-c", "qemu:///system", "checkpoint-list", "--domain", "vm", "--name");
        Answer answer = wrapper.execute(stoppedCommand(), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().startsWith("BACKUP_TRACKING_EXISTS:"));
        verify(domain).free();
    }

    @Test
    public void runningVmStillBlocksDirtyBitmaps() throws Exception {
        Domain domain = mock(Domain.class);
        when(resource.getDomain(connection, "vm")).thenReturn(domain);
        when(domain.isActive()).thenReturn(1);
        doReturn("").when(wrapper).run("virsh", "-c", "qemu:///system", "checkpoint-list", "--domain", "vm", "--name");
        when(domain.qemuMonitorCommand("{\"execute\":\"query-block\"}", 0))
                .thenReturn("{\"return\":[{\"inserted\":{\"dirty-bitmaps\":[{\"name\":\"backup\"}]}}]}");
        Answer answer = wrapper.execute(new CheckVmBackupTrackingCommand("vm"), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().startsWith("BACKUP_TRACKING_EXISTS:"));
        verify(domain).free();
    }
    @Test public void detectsLiveAndOfflineBitmaps() {
        assertTrue(LibvirtCheckVmBackupTrackingCommandWrapper.hasBitmaps(new JsonParser().parse("{\"return\":[{\"inserted\":{\"dirty-bitmaps\":[{\"name\":\"backup\"}]}}]}")));
        assertTrue(LibvirtCheckVmBackupTrackingCommandWrapper.hasBitmaps(new JsonParser().parse("[{\"format-specific\":{\"data\":{\"bitmaps\":[{\"name\":\"backup\"}]}}}]")));
    }
    @Test public void acceptsEmptyTrackingLists() {
        assertFalse(LibvirtCheckVmBackupTrackingCommandWrapper.hasBitmaps(new JsonParser().parse("{\"return\":[{\"inserted\":{\"dirty-bitmaps\":[]}}]}")));
        assertFalse(LibvirtCheckVmBackupTrackingCommandWrapper.hasBitmaps(new JsonParser().parse("[{\"format\":\"qcow2\"}]")));
    }
}

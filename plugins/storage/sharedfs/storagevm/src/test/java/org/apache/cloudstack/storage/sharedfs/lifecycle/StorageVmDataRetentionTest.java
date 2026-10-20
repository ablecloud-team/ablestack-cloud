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

package org.apache.cloudstack.storage.sharedfs.lifecycle;

import org.junit.Assert;
import org.junit.Test;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmService;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.utils.exception.CloudRuntimeException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicReference;

public class StorageVmDataRetentionTest {
    private static class Fixture {
        final StorageVmSharedFSLifeCycle life = new StorageVmSharedFSLifeCycle();
        final SharedFS fs = mock(SharedFS.class);
        final VolumeDao volumes = mock(VolumeDao.class);
        final VolumeApiService api = mock(VolumeApiService.class);
        final UserVmDao vms = mock(UserVmDao.class);
        final UserVmService userService = mock(UserVmService.class);
        final UserVmManager manager = mock(UserVmManager.class);
        Fixture() throws Exception {
            ReflectionTestUtils.setField(life,"volumeDao",volumes);ReflectionTestUtils.setField(life,"volumeApiService",api);
            ReflectionTestUtils.setField(life,"userVmDao",vms);ReflectionTestUtils.setField(life,"userVmService",userService);ReflectionTestUtils.setField(life,"userVmManager",manager);
            when(fs.getVmId()).thenReturn(41L);when(fs.getAccountId()).thenReturn(2L);
            UserVmVO vm=mock(UserVmVO.class);when(vm.getId()).thenReturn(41L);when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
            when(vms.findById(41L)).thenReturn(vm);when(manager.expunge(vm)).thenReturn(true);
        }
        VolumeVO volume(long id) {
            VolumeVO volume=mock(VolumeVO.class);AtomicReference<Long> attached=new AtomicReference<>(41L);
            when(volume.getId()).thenReturn(id);when(volume.getAccountId()).thenReturn(2L);when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
            when(volume.getInstanceId()).thenAnswer(call->attached.get());when(volume.getState()).thenReturn(Volume.State.Ready);
            when(volumes.findById(id)).thenReturn(volume);when(volumes.findByIdIncludingRemoved(id)).thenReturn(volume);
            when(api.detachVolumeViaDestroyVM(41L,id)).thenAnswer(call->{attached.set(null);return volume;});return volume;
        }
    }
    @Test public void preservesPrimaryAndAdditionalDataVolumesBeforeRemovingVm() throws Exception {
        Fixture f=new Fixture();VolumeVO a=f.volume(42),b=f.volume(43);
        when(f.volumes.findByInstanceAndType(41L,Volume.Type.DATADISK)).thenReturn(Arrays.asList(a,b));
        Assert.assertTrue(f.life.deleteSharedFS(f.fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,new LinkedHashSet<>(Arrays.asList(42L,43L))));
        org.mockito.InOrder order=inOrder(f.api,f.userService);order.verify(f.api).detachVolumeViaDestroyVM(41L,42L);order.verify(f.api).detachVolumeViaDestroyVM(41L,43L);order.verify(f.userService).destroyVm(41L,true);
        verify(f.api,never()).destroyVolume(anyLong(),any(),anyBoolean(),anyBoolean(),any());
        Assert.assertNull(a.getInstanceId());Assert.assertNull(b.getInstanceId());
    }
    @Test public void partialDetachFailureNeverRemovesVmOrDeletesAnyData() throws Exception {
        Fixture f=new Fixture();VolumeVO a=f.volume(42),b=f.volume(43);
        when(f.volumes.findByInstanceAndType(41L,Volume.Type.DATADISK)).thenReturn(Arrays.asList(a,b));
        doThrow(new CloudRuntimeException("injected detach failure")).when(f.api).detachVolumeViaDestroyVM(41L,43L);
        Assert.assertThrows(CloudRuntimeException.class,()->f.life.deleteSharedFS(f.fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,new LinkedHashSet<>(Arrays.asList(42L,43L))));
        verify(f.userService,never()).destroyVm(anyLong(),anyBoolean());verify(f.api,never()).destroyVolume(anyLong(),any(),anyBoolean(),anyBoolean(),any());
        Assert.assertNull(a.getInstanceId());Assert.assertEquals(Long.valueOf(41),b.getInstanceId());
    }
    @Test public void changedInventoryBlocksTheFirstDetach() throws Exception {
        Fixture f=new Fixture();VolumeVO a=f.volume(42),unplanned=f.volume(43);
        when(f.volumes.findByInstanceAndType(41L,Volume.Type.DATADISK)).thenReturn(Arrays.asList(a,unplanned));
        Assert.assertThrows(CloudRuntimeException.class,()->f.life.deleteSharedFS(f.fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,Collections.singleton(42L)));
        verify(f.api,never()).detachVolumeViaDestroyVM(anyLong(),anyLong());verify(f.userService,never()).destroyVm(anyLong(),anyBoolean());
    }
    @Test public void foreignDomainOrZoneIsRejectedBeforeTheFirstDetach() throws Exception {
        Fixture f=new Fixture();VolumeVO data=f.volume(42);
        when(f.volumes.findByInstanceAndType(41L,Volume.Type.DATADISK)).thenReturn(Collections.singletonList(data));
        when(data.getDomainId()).thenReturn(999L);
        Assert.assertThrows(CloudRuntimeException.class,()->f.life.deleteSharedFS(f.fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,Collections.singleton(42L)));
        when(data.getDomainId()).thenReturn(0L);when(data.getDataCenterId()).thenReturn(999L);
        Assert.assertThrows(CloudRuntimeException.class,()->f.life.deleteSharedFS(f.fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,Collections.singleton(42L)));
        verify(f.api,never()).detachVolumeViaDestroyVM(anyLong(),anyLong());verify(f.userService,never()).destroyVm(anyLong(),anyBoolean());
    }
    @Test public void tupleChangeAfterFirstDetachBlocksSecondDetachAndVmRemoval() throws Exception {
        Fixture f=new Fixture();VolumeVO a=f.volume(42),b=f.volume(43);
        when(f.volumes.findByInstanceAndType(41L,Volume.Type.DATADISK)).thenReturn(Arrays.asList(a,b));
        org.mockito.Mockito.doAnswer(call->{when(a.getInstanceId()).thenReturn(null);when(b.getPoolId()).thenReturn(999L);return a;}).when(f.api).detachVolumeViaDestroyVM(41L,42L);
        Assert.assertThrows(CloudRuntimeException.class,()->f.life.deleteSharedFS(f.fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,new LinkedHashSet<>(Arrays.asList(42L,43L))));
        verify(f.api,never()).detachVolumeViaDestroyVM(41L,43L);verify(f.userService,never()).destroyVm(anyLong(),anyBoolean());
        verify(f.api,never()).destroyVolume(anyLong(),any(),anyBoolean(),anyBoolean(),any());
    }
    @Test public void partiallyDeletedDataRetriesWithIncludingRemovedIdentityWithoutRepeatingDeletion() throws Exception {
        Fixture f=new Fixture();VolumeVO a=f.volume(42),b=f.volume(43);
        when(f.volumes.findByInstanceAndType(41L,Volume.Type.DATADISK)).thenAnswer(call->java.util.stream.Stream.of(a,b).filter(v->v.getInstanceId()!=null).collect(java.util.stream.Collectors.toList()));
        when(f.userService.destroyVm(41L,true)).thenAnswer(call->{when(f.vms.findById(41L)).thenReturn(null);return null;});
        java.util.concurrent.atomic.AtomicBoolean failed=new java.util.concurrent.atomic.AtomicBoolean();
        when(f.api.destroyVolume(org.mockito.Mockito.eq(42L),any(),anyBoolean(),anyBoolean(),any())).thenAnswer(call->{when(a.getState()).thenReturn(Volume.State.Expunged);when(f.volumes.findById(42L)).thenReturn(null);return a;});
        when(f.api.destroyVolume(org.mockito.Mockito.eq(43L),any(),anyBoolean(),anyBoolean(),any())).thenAnswer(call->{if(!failed.getAndSet(true))throw new CloudRuntimeException("own second deletion failure");when(b.getState()).thenReturn(Volume.State.Expunged);when(f.volumes.findById(43L)).thenReturn(null);return b;});
        try(org.mockito.MockedStatic<org.apache.cloudstack.context.CallContext> contexts=org.mockito.Mockito.mockStatic(org.apache.cloudstack.context.CallContext.class)){
            org.apache.cloudstack.context.CallContext context=mock(org.apache.cloudstack.context.CallContext.class);when(context.getCallingAccount()).thenReturn(mock(com.cloud.user.Account.class));contexts.when(org.apache.cloudstack.context.CallContext::current).thenReturn(context);
            java.util.Set<Long> ids=new LinkedHashSet<>(Arrays.asList(42L,43L));
            Assert.assertThrows(CloudRuntimeException.class,()->f.life.deleteSharedFS(f.fs,SharedFS.DataVolumePolicy.DELETE_VOLUMES,ids));
            Assert.assertTrue(f.life.deleteSharedFS(f.fs,SharedFS.DataVolumePolicy.DELETE_VOLUMES,ids));
        }
        verify(f.api,org.mockito.Mockito.times(1)).destroyVolume(org.mockito.Mockito.eq(42L),any(),anyBoolean(),anyBoolean(),any());
        verify(f.api,org.mockito.Mockito.times(2)).destroyVolume(org.mockito.Mockito.eq(43L),any(),anyBoolean(),anyBoolean(),any());
        verify(f.api,org.mockito.Mockito.times(1)).detachVolumeViaDestroyVM(41L,42L);verify(f.api,org.mockito.Mockito.times(1)).detachVolumeViaDestroyVM(41L,43L);
    }

    @Test public void dataRemovedDuringPreservationCannotBeClaimedAsRetained() throws Exception {
        Fixture f=new Fixture();VolumeVO a=f.volume(42),b=f.volume(43);
        when(f.volumes.findByInstanceAndType(41L,Volume.Type.DATADISK)).thenReturn(Arrays.asList(a,b));
        org.mockito.Mockito.doAnswer(call->{when(a.getInstanceId()).thenReturn(null);when(a.getState()).thenReturn(Volume.State.Destroy);return a;}).when(f.api).detachVolumeViaDestroyVM(41L,42L);
        Assert.assertThrows(CloudRuntimeException.class,()->f.life.deleteSharedFS(f.fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,new LinkedHashSet<>(Arrays.asList(42L,43L))));
        verify(f.api,never()).detachVolumeViaDestroyVM(41L,43L);verify(f.userService,never()).destroyVm(anyLong(),anyBoolean());
    }

}

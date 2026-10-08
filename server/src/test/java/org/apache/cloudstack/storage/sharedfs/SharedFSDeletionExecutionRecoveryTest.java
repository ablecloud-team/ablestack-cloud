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
package org.apache.cloudstack.storage.sharedfs;

import java.util.List;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;

public class SharedFSDeletionExecutionRecoveryTest {
    private static class Service extends SharedFSServiceImpl {
        SharedFSProvider provider;
        String failAudit;
        int compatibilityDeletes;
        @Override public SharedFSProvider getSharedFSProvider(String name) {return provider;}
        @Override public boolean stateTransitTo(SharedFS resource,SharedFS.Event event) {
            ReflectionTestUtils.setField(resource,"state",event==SharedFS.Event.OperationSucceeded?SharedFS.State.Expunged:SharedFS.State.Expunging);return true;
        }
        @Override protected void auditSharedFSDeletion(SharedFS resource,String phase) {if(phase.equals(failAudit))throw new CloudRuntimeException("audit unavailable");}
        @Override protected void deleteStorageServiceCompatibility(SharedFS resource) {compatibilityDeletes++;}
    }
    private Service service;
    private SharedFSVO fs;
    private SharedFSDao filesystems;
    private SharedFSLifeCycle lifecycle;
    private MockedStatic<CallContext> contexts;
    @Before public void setup() {
        service=new Service();fs=new SharedFSVO("fixture","",3L,2L,4L,"provider",SharedFS.Protocol.NFS,SharedFS.FileSystemType.XFS,5L);ReflectionTestUtils.setField(fs,"id",7L);ReflectionTestUtils.setField(fs,"state",SharedFS.State.Destroyed);fs.setVolumeId(42L);
        VolumeVO data=mock(VolumeVO.class);when(data.getId()).thenReturn(42L);when(data.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222");when(data.getVolumeType()).thenReturn(Volume.Type.DATADISK);when(data.getAccountId()).thenReturn(2L);when(data.getDomainId()).thenReturn(3L);when(data.getDataCenterId()).thenReturn(4L);when(data.getPoolId()).thenReturn(9L);when(data.getSize()).thenReturn(1024L);when(data.getInstanceId()).thenReturn(null);
        VolumeDao volumes=mock(VolumeDao.class);when(volumes.findById(42L)).thenReturn(data);when(volumes.findByIdIncludingRemoved(42L)).thenReturn(data);ReflectionTestUtils.setField(service,"volumeDao",volumes);
        fs.setDeletionPlanJson(service.createDeletionPlan(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES).toString());
        filesystems=mock(SharedFSDao.class);when(filesystems.findById(7L)).thenReturn(fs);when(filesystems.update(anyLong(),any())).thenReturn(true);when(filesystems.remove(7L)).thenReturn(true);ReflectionTestUtils.setField(service,"sharedFSDao",filesystems);
        ReflectionTestUtils.setField(service,"accountMgr",mock(AccountManager.class));
        lifecycle=mock(SharedFSLifeCycle.class);when(lifecycle.deleteSharedFS(eq(fs),eq(SharedFS.DataVolumePolicy.PRESERVE_VOLUMES),anySet())).thenReturn(true);service.provider=mock(SharedFSProvider.class);when(service.provider.getSharedFSLifeCycle()).thenReturn(lifecycle);
        contexts=mockStatic(CallContext.class);CallContext context=mock(CallContext.class);when(context.getCallingAccount()).thenReturn(mock(Account.class));contexts.when(CallContext::current).thenReturn(context);
    }
    @After public void close() {contexts.close();}
    @Test public void rowRemovalFailureRetriesCompletedReceiptWithoutRepeatingProviderEffects() {
        when(filesystems.remove(7L)).thenReturn(false,true);
        Assert.assertThrows(CloudRuntimeException.class,()->service.deleteSharedFSInternal(7L));
        Assert.assertTrue(service.completedDeletionReceipt(fs));Assert.assertEquals(SharedFS.State.Expunged,fs.getState());
        service.deleteSharedFSInternal(7L);verify(lifecycle,times(1)).deleteSharedFS(eq(fs),any(),anySet());verify(filesystems,times(2)).remove(7L);
    }
    @Test public void incompleteStartedAuditCannotInvokeProviderOrRemoveRow() {
        service.failAudit="STARTED";Assert.assertThrows(CloudRuntimeException.class,()->service.deleteSharedFSInternal(7L));verifyNoInteractions(lifecycle);verify(filesystems,never()).remove(anyLong());Assert.assertFalse(service.completedDeletionReceipt(fs));
    }
    @Test public void completionAuditFailurePreservesRowAndDoesNotClaimCompleteReceipt() {
        service.failAudit="COMPLETE";Assert.assertThrows(CloudRuntimeException.class,()->service.deleteSharedFSInternal(7L));Assert.assertFalse(service.completedDeletionReceipt(fs));verify(filesystems,never()).remove(anyLong());Assert.assertEquals(0,service.compatibilityDeletes);
    }
    @Test public void providerFailureNeverRemovesServiceOrPromotesCompletionReceipt() {
        when(lifecycle.deleteSharedFS(eq(fs),any(),anySet())).thenReturn(false);Assert.assertThrows(CloudRuntimeException.class,()->service.deleteSharedFSInternal(7L));verify(filesystems,never()).remove(anyLong());Assert.assertFalse(service.completedDeletionReceipt(fs));
    }
}

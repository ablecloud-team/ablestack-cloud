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
package com.cloud.vm;

import java.util.*;
import com.cloud.storage.*;
import com.cloud.storage.dao.*;
import com.cloud.user.*;
import com.cloud.vm.dao.*;
import com.cloud.exception.*;
import org.apache.cloudstack.api.command.user.vm.CloneVMCmd;
import org.apache.cloudstack.api.response.VmCloneBatchResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class VmCloneBatchManagerTest {
    private VmCloneBatchManager manager;
    private UserVmVO source, target;
    private VolumeVO root, copy;
    private VmCloneBatchVO row;
    private VmCloneBatchData data;
    private CloneVMCmd cmd;
    private final String batch="11111111-1111-4111-8111-111111111111", retry="22222222-2222-4222-8222-222222222222";
    @Before public void setup() {
        manager=new VmCloneBatchManager(){@Override protected <T>T locked(String name,java.util.function.Supplier<T> work){return work.get();}};
        manager.batches=mock(VmCloneBatchDao.class);manager.vms=mock(UserVmDao.class);manager.volumes=mock(VolumeDao.class);manager.volumeDetails=mock(VolumeDetailsDao.class);manager.vmDetails=mock(VMInstanceDetailsDao.class);manager.accounts=mock(AccountManager.class);manager.jobs=mock(AsyncJobDao.class);
        CallContext.register(mock(UserVO.class),mock(AccountVO.class));
        source=mock(UserVmVO.class);when(source.getId()).thenReturn(10L);when(source.getUuid()).thenReturn("source");when(source.getAccountId()).thenReturn(2L);when(source.getServiceOfferingId()).thenReturn(3L);when(source.getTemplateId()).thenReturn(4L);when(manager.vms.findById(10L)).thenReturn(source);when(manager.vms.findByIdIncludingRemoved(10L)).thenReturn(source);
        root=mock(VolumeVO.class);when(root.getId()).thenReturn(20L);when(root.getUuid()).thenReturn("root");when(root.getPoolId()).thenReturn(30L);when(root.getFormat()).thenReturn(Storage.ImageFormat.RAW);when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);when(manager.volumes.findByInstance(10L)).thenReturn(List.of(root));when(manager.volumeDetails.listDetailsKeyPairs(anyLong())).thenReturn(Collections.emptyMap());
        cmd=mock(CloneVMCmd.class);when(cmd.getId()).thenReturn(10L);when(cmd.getName()).thenReturn("copy");when(cmd.getCount()).thenReturn(2);when(cmd.getType()).thenReturn("full");when(cmd.getBatchId()).thenReturn(batch);when(cmd.getInjectedJobId()).thenReturn(retry);when(cmd.getRetryIndices()).thenReturn(List.of(2));
        data=new VmCloneBatchData();data.name="copy";data.count=2;data.sourceOfferingId=3L;data.sourceTemplateId=4L;data.type="full";data.sourceUuid="source";data.currentAttempt=batch;data.status="PARTIAL";
        VmCloneBatchData.Attempt attempt=new VmCloneBatchData.Attempt();attempt.finished=true;attempt.indices=List.of(1,2);data.attempts.put(batch,attempt);
        VmCloneBatchData.SourceDisk spec=new VmCloneBatchData.SourceDisk();spec.id=20L;spec.uuid="root";spec.pool=30L;spec.size=0L;spec.device=0L;spec.format="RAW";spec.policy="{}";spec.type="ROOT";data.sourceDisks.add(spec);
        for(int i=1;i<=2;i++){VmCloneBatchData.Item item=new VmCloneBatchData.Item();item.index=i;item.name="copy-"+i;data.items.add(item);}
        row=new VmCloneBatchVO(batch,10L,2L,1L,VmCloneBatchManager.fingerprint(cmd),"{}");when(manager.batches.findByUuid(batch)).thenAnswer(inv->{row.setData(new com.google.gson.Gson().toJson(data),data.status);return row;});
        target=mock(UserVmVO.class);when(target.getId()).thenReturn(40L);when(target.getUuid()).thenReturn("target");when(target.getName()).thenReturn("copy-2");when(target.getAccountId()).thenReturn(2L);when(target.getState()).thenReturn(VirtualMachine.State.Stopped);when(manager.vms.findByIdIncludingRemoved(40L)).thenReturn(target);
        copy=mock(VolumeVO.class);when(copy.getId()).thenReturn(50L);when(copy.getUuid()).thenReturn("copy-root");when(copy.getVolumeType()).thenReturn(Volume.Type.ROOT);when(copy.getState()).thenReturn(Volume.State.Ready);when(copy.getAccountId()).thenReturn(2L);when(copy.getInstanceId()).thenReturn(40L);when(manager.volumes.findByUuidIncludingRemoved("copy-root")).thenReturn(copy);
    }
    @After public void teardown(){CallContext.unregister();}
    private VmCloneBatchData.Item allocated(){var item=data.items.get(1);item.vmId=40L;item.vmRecorded=true;var disk=new VmCloneBatchData.Disk();disk.sourceId=20L;disk.uuid="copy-root";disk.type="ROOT";item.disks.add(disk);return item;}
    @Test public void originalSuccessIsNeverRecreatedEvenAfterDeletion(){var item=allocated();item.created=true;when(target.getRemoved()).thenReturn(new Date());assertEquals("NONE",manager.action(row,data,item));}
    @Test public void startFailureRetriesExistingVmOnly(){var item=allocated();data.start=true;assertEquals("START",manager.action(row,data,item));when(target.getState()).thenReturn(VirtualMachine.State.Running);assertEquals("NONE",manager.action(row,data,item));}
    @Test public void uncertainOrPendingDiskBlocksCreate(){var item=allocated();item.vmId=null;when(copy.getInstanceId()).thenReturn(null);when(copy.getState()).thenReturn(Volume.State.Creating);assertEquals("NONE",manager.action(row,data,item));when(copy.getRemoved()).thenReturn(new Date());when(copy.getState()).thenReturn(Volume.State.Destroy);assertEquals("NONE",manager.action(row,data,item));when(copy.getState()).thenReturn(Volume.State.Expunged);assertEquals("CREATE",manager.action(row,data,item));}
    @Test public void changedSourcePreventsRecreatingUnattemptedTarget(){assertEquals("CREATE",manager.action(row,data,data.items.get(1)));when(root.getPoolId()).thenReturn(31L);assertEquals("NONE",manager.action(row,data,data.items.get(1)));}
    @Test public void duplicateAttemptReplaysButCannotChangeIndices(){data.attempts.put(retry,data.attempts.get(batch));when(cmd.getRetryIndices()).thenReturn(List.of(1,2));assertTrue(manager.preflight(cmd,source));when(cmd.getRetryIndices()).thenReturn(List.of(2));assertThrows(InvalidParameterValueException.class,()->manager.preflight(cmd,source));}
    @Test public void retryNonceFromAnotherJobCannotAttachToThisBatch(){when(manager.jobs.findByUuidIncludingRemoved(retry)).thenReturn(mock(AsyncJobVO.class));assertThrows(InvalidParameterValueException.class,()->manager.preflight(cmd,source));}
    @Test public void invalidRetryIndicesAreRejected(){when(cmd.getRetryIndices()).thenReturn(List.of(2,2));assertThrows(InvalidParameterValueException.class,()->VmCloneBatchManager.indices(cmd,2));when(cmd.getRetryIndices()).thenReturn(List.of(3));assertThrows(InvalidParameterValueException.class,()->VmCloneBatchManager.indices(cmd,2));}
    @Test public void sourceOwnershipChangeReturnsNotfoundWithoutIdentifiers(){when(source.getAccountId()).thenReturn(9L);var response=manager.query(batch);assertEquals("NOTFOUND",response.status);assertNull(response.sourceId);assertTrue(response.targets.isEmpty());}
    @Test public void foreignTargetRedactsUuidAndRawErrorEvenWhenRemoved(){var item=allocated();item.error="sensitive target UUID";data.error="sensitive global error";when(target.getRemoved()).thenReturn(new Date());doThrow(new PermissionDeniedException("denied")).when(manager.accounts).checkAccess(any(),isNull(),eq(true),eq(target));VmCloneBatchResponse response=manager.query(batch);assertNull(response.targets.get(1).id);assertEquals("ACCESS_RESTRICTED",response.targets.get(1).phase);assertNull(response.error);assertTrue(response.targets.get(1).volumes.isEmpty());}
    @Test public void lostFinalCheckpointReconcilesCreationAndBlocksRecreation(){var item=allocated();when(target.getRemoved()).thenReturn(null);when(manager.batches.update(anyLong(),any())).thenReturn(true);var response=manager.query(batch);assertTrue(response.targets.get(1).created);assertEquals("NONE",response.targets.get(1).retryAction);}
}

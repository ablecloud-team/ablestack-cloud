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
import org.junit.*;
import org.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import com.google.gson.*;
import com.cloud.storage.*;
import com.cloud.storage.dao.*;
import com.cloud.user.*;
import com.cloud.vm.*;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.utils.db.SearchCriteria;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.apache.cloudstack.storage.dataservice.*;
import org.apache.cloudstack.storage.dataservice.dao.*;
import java.util.*;
public class SharedFSDeletionImpactTest {
    SharedFSServiceImpl service;SharedFSVO fs;VolumeDao volumes;UserVmDao vms;StorageFileShareDao shares;StorageBlockTargetDao blocks;
    List<VolumeVO> attached;Map<Long,VolumeVO> byId;MockedStatic<CallContext> context;
    @Before public void open(){
        service=new SharedFSServiceImpl();fs=mock(SharedFSVO.class);when(fs.getId()).thenReturn(9L);when(fs.getUuid()).thenReturn("fs");when(fs.getName()).thenReturn("preserve");when(fs.getVmId()).thenReturn(7L);when(fs.getVolumeId()).thenReturn(2L);when(fs.getAccountId()).thenReturn(1L);when(fs.getDataCenterId()).thenReturn(13L);
        when(fs.getFsProviderName()).thenReturn("SHAREDFSVM");SharedFSProvider provider=mock(SharedFSProvider.class);SharedFSLifeCycle life=mock(SharedFSLifeCycle.class);when(life.supportsDataPreservingDelete()).thenReturn(true);when(provider.getSharedFSLifeCycle()).thenReturn(life);ReflectionTestUtils.setField(service,"sharedFSProviderMap",java.util.Collections.singletonMap("SHAREDFSVM",provider));
        SharedFSDao dao=mock(SharedFSDao.class);when(dao.findByUuidIncludingRemoved("fs")).thenReturn(fs);
        AccountManager accounts=mock(AccountManager.class);context=mockStatic(CallContext.class);CallContext cc=mock(CallContext.class);context.when(CallContext::current).thenReturn(cc);when(cc.getCallingAccount()).thenReturn(mock(Account.class));
        volumes=mock(VolumeDao.class);vms=mock(UserVmDao.class);shares=mock(StorageFileShareDao.class);blocks=mock(StorageBlockTargetDao.class);
        UserVmVO vm=mock(UserVmVO.class);when(vm.getUuid()).thenReturn("vm");when(vm.getAccountId()).thenReturn(1L);when(vm.getDataCenterId()).thenReturn(13L);when(vm.getUserVmType()).thenReturn(UserVmManager.SHAREDFSVM);when(vms.findByIdIncludingRemoved(7L)).thenReturn(vm);
        StorageServiceInstanceDao instances=mock(StorageServiceInstanceDao.class);StorageServiceInstanceVO i=mock(StorageServiceInstanceVO.class);when(i.getId()).thenReturn(20L);when(i.getUuid()).thenReturn("instance");when(instances.findByVmId(7L)).thenReturn(i);
        when(shares.createSearchCriteria()).thenAnswer(x->mock(SearchCriteria.class));when(blocks.createSearchCriteria()).thenAnswer(x->mock(SearchCriteria.class));
        byId=new HashMap<>();for(long id=1;id<=4;id++){VolumeVO v=mock(VolumeVO.class);when(v.getId()).thenReturn(id);when(v.getUuid()).thenReturn("volume-"+id);when(v.getName()).thenReturn("disk-"+id);when(v.getAccountId()).thenReturn(1L);when(v.getDataCenterId()).thenReturn(13L);when(v.getVolumeType()).thenReturn(id==1?Volume.Type.ROOT:Volume.Type.DATADISK);when(v.getSize()).thenReturn(1024L);when(v.getPoolId()).thenReturn(5L);when(v.getState()).thenReturn(Volume.State.Ready);if(id!=3)when(v.getInstanceId()).thenReturn(7L);byId.put(id,v);}
        attached=Arrays.asList(byId.get(1L),byId.get(2L),byId.get(4L));when(volumes.findByInstance(7L)).thenAnswer(x->attached);when(volumes.findByIdIncludingRemoved(anyLong())).thenAnswer(x->byId.get(x.getArgument(0)));
        StorageFileShareVO file=mock(StorageFileShareVO.class);when(file.getVolumeId()).thenReturn(3L);when(shares.listByInstanceIdAndProtocol(eq(20L),any())).thenReturn(Arrays.asList(file,file));
        for(Object[] item:new Object[][]{{"sharedFSDao",dao},{"accountMgr",accounts},{"volumeDao",volumes},{"userVmDao",vms},{"storageServiceInstanceDao",instances},{"storageFileShareDao",shares},{"storageBlockTargetDao",blocks},{"diskOfferingDao",mock(DiskOfferingDao.class)}})ReflectionTestUtils.setField(service,(String)item[0],item[1]);
    }
    @After public void close(){context.close();}
    @Test public void unionIncludesRootPrimaryAdditionalAndUnreferencedHiddenDataOnce(){
        JsonObject p=service.getDeletionImpact("fs");assertEquals(4,p.getAsJsonArray("volumes").size());assertEquals("PRESERVE",p.get("defaultpolicy").getAsString());
        Set<String> ids=new HashSet<>();for(JsonElement e:p.getAsJsonArray("volumes"))ids.add(e.getAsJsonObject().get("uuid").getAsString());assertEquals(new HashSet<>(Arrays.asList("volume-1","volume-2","volume-3","volume-4")),ids);
        verify(volumes,never()).remove(anyLong());
    }
    @Test public void foreignVmIsAVisibleConflictNotAnApprovedDeleteCandidate(){
        when(byId.get(2L).getInstanceId()).thenReturn(999L);UserVmVO other=mock(UserVmVO.class);when(other.getUuid()).thenReturn("foreign");when(vms.findByIdIncludingRemoved(999L)).thenReturn(other);
        JsonObject p=service.getDeletionImpact("fs");assertTrue(p.get("blocked").getAsBoolean());assertFalse(p.getAsJsonArray("volumes").get(1).getAsJsonObject().get("deletable").getAsBoolean());
    }
    @Test public void rootProtectionRemainsVisibleAndRepeatedImpactFingerprintIsStable(){
        when(byId.get(1L).isDeleteProtection()).thenReturn(true);JsonObject a=service.getDeletionImpact("fs"),b=service.getDeletionImpact("fs");
        assertTrue(a.get("rootdeleteprotected").getAsBoolean());assertEquals(a.get("fingerprint"),b.get("fingerprint"));
    }
    @Test(expected=com.cloud.utils.exception.CloudRuntimeException.class) public void wrongVolumeOwnerFailsClosed(){when(byId.get(3L).getAccountId()).thenReturn(99L);service.getDeletionImpact("fs");}
}

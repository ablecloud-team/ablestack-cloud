// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.util.List;
import com.cloud.storage.VolumeVO;
import com.cloud.exception.InvalidParameterValueException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageVolumeQualifiedSharePathTest {
    private static class Manager extends StorageServiceManagerImpl {VolumeVO volume;@Override protected VolumeVO requireVolume(Long id){return volume;}}
    private Manager manager;private StorageServiceInstanceVO instance;private org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao shares;
    @Before public void setup(){manager=new Manager();manager.volume=Mockito.mock(VolumeVO.class);Mockito.when(manager.volume.getId()).thenReturn(1000L);Mockito.when(manager.volume.getUuid()).thenReturn("b6430014-9203-41e0-93a9-26840809092f");instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(7L);shares=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class);Mockito.when(shares.listByInstanceIdAndProtocol(Mockito.anyLong(),Mockito.any())).thenReturn(List.of());ReflectionTestUtils.setField(manager,"storageFileShareDao",shares);}
    private StorageFileShareVO existing(long volume,String path,String physical,String relative,StorageServiceInstance.Protocol protocol){StorageFileShareVO s=Mockito.mock(StorageFileShareVO.class);Mockito.when(s.getId()).thenReturn(12L);Mockito.when(s.getVolumeId()).thenReturn(volume);Mockito.when(s.getPath()).thenReturn(path);Mockito.when(s.getProtocol()).thenReturn(protocol);Mockito.when(s.getState()).thenReturn(StorageServiceInstance.ResourceState.Ready);Mockito.when(s.getConfigJson()).thenReturn("{\"backingPath\":\""+physical+"\",\"relativeSharePath\":\""+relative+"\",\"volumeMountPath\":\"/export\"}");return s;}
    @Test public void newOldVolumeChildRelativeTextCanMatchAnotherVolumeNfsParentOnlyWhenPhysicalRootsAreDisjoint(){StorageFileShareVO nfs=existing(1001,"/export/published-parent","/srv/ablestack-storage/volumes/27f6da7e-a4d6-4cfe-a9e4-7c0d57d313ef/tree/parent","tree/parent",StorageServiceInstance.Protocol.NFS);Mockito.when(shares.listByInstanceIdAndProtocol(7L,StorageServiceInstance.Protocol.NFS)).thenReturn(List.of(nfs));manager.validateFileSharePathAvailable(instance,"/export/smb-child",null,1000L,"SMB share",false,"tree/parent/child");Assert.assertEquals("/srv/ablestack-storage/volumes/b6430014-9203-41e0-93a9-26840809092f/tree/parent/child",manager.resolveFileShareBackingPath(instance,manager.volume,"MOUNT_EXISTING","tree/parent/child","/export/smb-child","smb-child"));}
    @Test public void sameVolumeSamePathStillNeedsReviewedCrossProtocolPolicyAndRealCrossVolumeOverlapRejects(){StorageFileShareVO nfs=existing(1000,"/export/nfs","/export/shared","shared",StorageServiceInstance.Protocol.NFS);
        Mockito.when(shares.listByInstanceIdAndProtocol(7L,StorageServiceInstance.Protocol.NFS)).thenReturn(List.of(nfs));
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.validateFileSharePathAvailable(instance,"/export/smb",null,1000L,"SMB share",false,"shared"));
        manager.validateFileSharePathAvailable(instance,"/export/smb",null,1000L,"SMB share",true,"shared");
        StorageFileShareVO foreign=existing(1001,"/export/foreign","/srv/ablestack-storage/volumes/b6430014-9203-41e0-93a9-26840809092f/shared","shared",StorageServiceInstance.Protocol.NFS);
        Mockito.when(shares.listByInstanceIdAndProtocol(7L,StorageServiceInstance.Protocol.NFS)).thenReturn(List.of(foreign));
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.validateFileSharePathAvailable(instance,"/export/smb",null,1000L,"SMB share",false,"shared/child"));
        }
    @Test public void existingReadyLegacyRecordedRootIsNotRewrittenByTheNewRelativePathRule(){StorageFileShareVO old=existing(1000,"/export/old","/export/legacy/child","legacy/child",StorageServiceInstance.Protocol.SMB);Mockito.when(shares.listByInstanceIdAndProtocol(7L,StorageServiceInstance.Protocol.SMB)).thenReturn(List.of(old));Assert.assertEquals("/export/legacy/child",manager.resolveFileShareBackingPath(instance,manager.volume,"MOUNT_EXISTING","legacy/child","/export/old","old"));Assert.assertEquals("/export/legacy/child",manager.resolveSmbRuntimeBackingPath(instance,old));}
}

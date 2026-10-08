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
package org.apache.cloudstack.storage.dataservice;
import com.cloud.storage.*;
import com.cloud.storage.dao.VolumeDao;
import com.google.gson.*;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.junit.*;
import org.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
public class StorageFileBackingReuseIdentityTest {
    private static class Manager extends StorageServiceManagerImpl {
        @Override protected boolean canReuseManagedAttachedFileShareVolume(StorageServiceInstanceVO instance,StorageFileShareVO share,VolumeVO volume,Long vm){return true;}
        @Override protected JsonObject createFileShareVolumePayload(StorageServiceInstanceVO instance,StorageFileShareVO share,VolumeVO volume){JsonObject request=new JsonObject();request.addProperty("volumeUuid",volume.getUuid());return request;}
    }
    private Manager manager;private StorageServiceInstanceVO instance;private StorageFileShareVO share;private StorageServiceGuestCommandDispatcher guest;private StorageFileShareDao shares;
    @Before public void setup(){
        manager=new Manager();ReflectionTestUtils.setField(manager,"configurationVolumeDetailsDao",Mockito.mock(com.cloud.storage.dao.VolumeDetailsDao.class));instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(7L);VolumeVO volume=Mockito.mock(VolumeVO.class);Mockito.when(volume.getInstanceId()).thenReturn(7L);Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);Mockito.when(volume.getUuid()).thenReturn("actual-volume");VolumeDao volumes=Mockito.mock(VolumeDao.class);Mockito.when(volumes.findById(12L)).thenReturn(volume);ReflectionTestUtils.setField(manager,"volumeDao",volumes);
        share=new StorageFileShareVO(6,StorageServiceInstance.Protocol.SMB,"legacy-reused","/legacy-reused",12L,"XFS",null,StorageServiceInstance.ResourceState.Ready,"{}");guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);shares=Mockito.mock(StorageFileShareDao.class);ReflectionTestUtils.setField(manager,"guestCommandDispatcher",guest);ReflectionTestUtils.setField(manager,"storageFileShareDao",shares);
    }
    private String observed(String filesystem){JsonObject value=new JsonObject();value.addProperty("success",true);value.addProperty("volumeUuid","actual-volume");value.addProperty("filesystemUuid",filesystem);value.addProperty("serial","serial-volume");value.addProperty("matchedBy","SERIAL");value.addProperty("filesystem","xfs");return value.toString();}
    @Test public void reusedSharedBackingRecordsItsOwnFreshGuestUuidAndInspection(){
        Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"observed",observed("actual-fs")));
        manager.prepareFileShareBackingVolume(instance,share,"FORMAT_IF_EMPTY");JsonObject config=new JsonParser().parse(share.getConfigJson()).getAsJsonObject();Assert.assertEquals("actual-fs",config.get("filesystemUuid").getAsString());Assert.assertTrue(config.has("lastInspection"));ArgumentCaptor<StorageServiceGuestCommand> request=ArgumentCaptor.forClass(StorageServiceGuestCommand.class);Mockito.verify(guest).dispatch(request.capture());Assert.assertEquals("MOUNT_EXISTING",new JsonParser().parse(request.getValue().getPayload()).getAsJsonObject().get("importMode").getAsString());
    }
    @Test public void changedExplicitFilesystemUuidCannotBeSilentlyOverwrittenDuringReuse(){
        JsonObject config=new JsonObject();config.addProperty("filesystemUuid","expected-fs");share.setConfigJson(config.toString());Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"observed",observed("other-fs")));
        Assert.assertThrows(CloudRuntimeException.class,()->manager.prepareFileShareBackingVolume(instance,share,"MOUNT_EXISTING"));Mockito.verify(shares,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
    }
}

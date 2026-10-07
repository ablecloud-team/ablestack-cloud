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
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.storage.VolumeVO;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;

public class StorageConfigTransientDirectoryTest {
    static class Manager extends StorageServiceManagerImpl {
        VolumeVO volume;
        protected Long configurationVolumeId(StorageServiceInstanceVO instance,String uuid) { return 45L; }
        protected VolumeVO requireVolume(Long id) { return volume; }
        protected JsonObject createFileShareVolumePayload(StorageServiceInstanceVO instance,StorageFileShareVO share,VolumeVO volume) {
            JsonObject payload=new JsonObject();payload.addProperty("volumeUuid",volume.getUuid());payload.add("config",new JsonParser().parse(share.getConfigJson()));return payload;
        }
    }
    @Test public void nativeDirectoryPreparationDoesNotUpdateAnUnpersistedShareVo() {
        Manager manager=new Manager();StorageServiceGuestCommandDispatcher dispatcher=Mockito.mock(StorageServiceGuestCommandDispatcher.class);StorageFileShareDao shares=Mockito.mock(StorageFileShareDao.class);
        ReflectionTestUtils.setField(manager,"guestCommandDispatcher",dispatcher);ReflectionTestUtils.setField(manager,"storageFileShareDao",shares);
        manager.volume=Mockito.mock(VolumeVO.class);Mockito.when(manager.volume.getUuid()).thenReturn("bound-volume");
        StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(48L);
        Mockito.when(dispatcher.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"ok","{\"success\":true,\"volumeUuid\":\"bound-volume\"}"));
        manager.prepareConfigurationDirectory(instance,"bound-volume","smb/reviewed");
        org.mockito.ArgumentCaptor<StorageServiceGuestCommand> request=org.mockito.ArgumentCaptor.forClass(StorageServiceGuestCommand.class);Mockito.verify(dispatcher).dispatch(request.capture());
        JsonObject payload=new JsonParser().parse(request.getValue().getPayload()).getAsJsonObject();Assert.assertEquals("MOUNT_EXISTING",payload.get("importMode").getAsString());
        Assert.assertTrue(payload.getAsJsonObject("config").get("createDirectory").getAsBoolean());
        Assert.assertEquals("smb/reviewed",payload.getAsJsonObject("config").get("relativeSharePath").getAsString());Mockito.verifyNoInteractions(shares);
    }
}

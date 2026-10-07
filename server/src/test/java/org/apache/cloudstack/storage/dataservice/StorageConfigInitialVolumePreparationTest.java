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
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageConfigInitialVolumePreparationTest {
    static class Manager extends StorageServiceManagerImpl {
        VolumeVO volume;
        protected VolumeVO configurationInitialVolume(StorageServiceInstanceVO instance) { return volume; }
        protected Long configurationVolumeId(StorageServiceInstanceVO instance, String uuid) { return 45L; }
        protected int backingVolumeFormatDeadline(long bytes) { return 420; }
    }
    @Test public void onlyExplicitNewInitialDataUsesEmptyFormattingAndExistingAlwaysMounts() {
        Manager manager = new Manager();StorageServiceGuestCommandDispatcher dispatcher = Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        ReflectionTestUtils.setField(manager, "guestCommandDispatcher", dispatcher);
        StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(43L);Mockito.when(instance.getUuid()).thenReturn("instance");
        VolumeVO volume = Mockito.mock(VolumeVO.class);manager.volume=volume;Mockito.when(volume.getUuid()).thenReturn("volume");Mockito.when(volume.getSize()).thenReturn(21474836480L);
        Mockito.when(dispatcher.dispatch(Mockito.any())).thenAnswer(call -> {
            StorageServiceGuestCommand command = call.getArgument(0);
            Assert.assertEquals("volume attach inspect",command.getOperation());Assert.assertEquals(540,command.getTimeoutSeconds());
            JsonObject payload=new JsonParser().parse(command.getPayload()).getAsJsonObject();
            Assert.assertEquals("/srv/ablestack-storage/volumes/volume",payload.get("mountPath").getAsString());
            return new StorageServiceGuestCommandResult(true,"ok","{\"success\":true,\"volumeUuid\":\"volume\",\"filesystemUuid\":\"preserved-fs\"}");
        });
        JsonObject blueprint = new JsonObject();blueprint.addProperty("backingvolumemode","EXISTING");blueprint.addProperty("filesystem","XFS");
        manager.prepareConfigurationInitialVolume(instance,blueprint);
        org.mockito.ArgumentCaptor<StorageServiceGuestCommand> request = org.mockito.ArgumentCaptor.forClass(StorageServiceGuestCommand.class);
        Mockito.verify(dispatcher).dispatch(request.capture());
        Assert.assertEquals("MOUNT_EXISTING",new JsonParser().parse(request.getValue().getPayload()).getAsJsonObject().get("importMode").getAsString());
        Mockito.clearInvocations(dispatcher);blueprint.addProperty("backingvolumemode","NEW");manager.prepareConfigurationInitialVolume(instance,blueprint);
        Mockito.verify(dispatcher).dispatch(request.capture());
        Assert.assertEquals("FORMAT_IF_EMPTY",new JsonParser().parse(request.getValue().getPayload()).getAsJsonObject().get("importMode").getAsString());
    }
    @Test public void missingObservedFilesystemCannotCompleteNewInitialPreparation() {
        Manager manager = new Manager();StorageServiceGuestCommandDispatcher dispatcher = Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        ReflectionTestUtils.setField(manager,"guestCommandDispatcher",dispatcher);
        StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(43L);
        manager.volume=Mockito.mock(VolumeVO.class);Mockito.when(manager.volume.getUuid()).thenReturn("volume");Mockito.when(manager.volume.getSize()).thenReturn(21474836480L);
        Mockito.when(dispatcher.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"ok","{\"success\":true,\"volumeUuid\":\"volume\"}"));
        Assert.assertThrows(CloudRuntimeException.class,()->manager.prepareConfigurationInitialVolume(instance,new JsonObject()));
    }
}
